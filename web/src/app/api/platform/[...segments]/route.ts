import { cookies } from "next/headers";
import {
  bearerAuthorizationHeader,
  findOperatorSession,
  sessionCookieName,
  updateOperatorSessionTokens,
} from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";
import {
  declarationTenantCookieName,
  parseDeclarationTenantCookie,
} from "@/lib/declaration-draft";

type TokenReply = {
  accessToken?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
};

async function trySilentRefresh(
  sessionId: string,
  refreshToken: string,
): Promise<{ accessToken: string; refreshToken: string } | undefined> {
  let refreshReply: Response;
  try {
    refreshReply = await fetch(`${platformApiBase}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    });
  } catch {
    return undefined;
  }
  if (!refreshReply.ok) return undefined;
  const tokens = (await refreshReply.json()) as TokenReply;
  const accessToken = typeof tokens.accessToken === "string" ? tokens.accessToken : "";
  const newRefresh = typeof tokens.refreshToken === "string" ? tokens.refreshToken : "";
  const expiresIn = typeof tokens.expiresIn === "number" ? tokens.expiresIn : 0;
  if (!accessToken || !newRefresh || expiresIn <= 0) return undefined;
  await updateOperatorSessionTokens(sessionId, {
    accessToken,
    refreshToken: newRefresh,
    accessExpiresAtMillis: Date.now() + expiresIn * 1000,
  });
  return { accessToken, refreshToken: newRefresh };
}

// Platform proxy — 平台代理：把 /api/platform/<路径> 转发到平台 /api/v1/<路径>，
// 由服务端带上会话里的 Bearer；401 时静默刷新一次后再试。
async function forwardToPlatform(
  request: Request,
  context: { params: Promise<{ segments: string[] }> },
): Promise<Response> {
  const cookieJar = await cookies();
  const sessionId = cookieJar.get(sessionCookieName)?.value;
  const session = await findOperatorSession(sessionId);
  if (!session || !sessionId) {
    return Response.json({ reason: "signed-out" }, { status: 401 });
  }

  const { segments } = await context.params;
  const platformPath = segments.map(encodeURIComponent).join("/");
  const query = new URL(request.url).search;
  // SHARED-3: multipart must keep boundary Content-Type + binary body (not request.text()).
  // SHARED-3：multipart 须保留带 boundary 的 Content-Type，并用二进制体（不可 request.text()）。
  const inboundContentType = request.headers.get("content-type") ?? "";
  const isMultipart = inboundContentType.toLowerCase().includes("multipart/form-data");
  let requestBody: ArrayBuffer | string | undefined;
  if (request.method !== "GET" && request.method !== "HEAD") {
    requestBody = isMultipart ? await request.arrayBuffer() : await request.text();
  }

  async function callUpstream(accessToken: string): Promise<Response> {
    const forwardedHeaders: Record<string, string> = {
      authorization: bearerAuthorizationHeader(accessToken),
      accept: request.headers.get("accept") ?? "application/json",
    };
    if (requestBody !== undefined) {
      if (isMultipart || inboundContentType) {
        forwardedHeaders["content-type"] = inboundContentType || "application/json";
      } else {
        forwardedHeaders["content-type"] = "application/json";
      }
    }
    // Prefer client X-Tenant-Id; else declaration tenant cookie (pages/forms runtime).
    // 优先请求头；否则用声明租户 cookie（页面/表单运行时）。
    const tenantFromRequest = request.headers.get("X-Tenant-Id")?.trim() ?? "";
    const tenantFromCookie = parseDeclarationTenantCookie(
      cookieJar.get(declarationTenantCookieName)?.value,
    );
    const tenantId = tenantFromRequest || tenantFromCookie;
    if (tenantId) forwardedHeaders["X-Tenant-Id"] = tenantId;
    // Config-5c: pass optimistic-concurrency headers through to platform-app.
    // 配置乐观并发头透传至 platform-app。
    const ifMatch = request.headers.get("If-Match");
    if (ifMatch) forwardedHeaders["If-Match"] = ifMatch;
    const ifNoneMatch = request.headers.get("If-None-Match");
    if (ifNoneMatch) forwardedHeaders["If-None-Match"] = ifNoneMatch;
    return fetch(`${platformApiBase}/api/v1/${platformPath}${query}`, {
      method: request.method,
      headers: forwardedHeaders,
      body: requestBody,
      cache: "no-store",
    });
  }

  let upstreamReply: Response;
  try {
    upstreamReply = await callUpstream(session.accessToken);
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }

  if (upstreamReply.status === 401) {
    const refreshed = await trySilentRefresh(sessionId, session.refreshToken);
    if (!refreshed) {
      return Response.json({ reason: "signed-out" }, { status: 401 });
    }
    try {
      upstreamReply = await callUpstream(refreshed.accessToken);
    } catch {
      return Response.json({ reason: "platform-unreachable" }, { status: 502 });
    }
  }

  const replyHeaders: Record<string, string> = {
    "content-type": upstreamReply.headers.get("content-type") ?? "application/json",
  };
  const etag = upstreamReply.headers.get("etag");
  if (etag) replyHeaders["etag"] = etag;
  return new Response(upstreamReply.body, {
    status: upstreamReply.status,
    headers: replyHeaders,
  });
}

// GET/PUT/POST/PATCH/DELETE — includes declaration migrations enqueue/review/apply/cancel POSTs.
export const GET = forwardToPlatform;
export const PUT = forwardToPlatform;
export const POST = forwardToPlatform;
export const PATCH = forwardToPlatform;
export const DELETE = forwardToPlatform;
