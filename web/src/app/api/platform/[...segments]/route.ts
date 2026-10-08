import { cookies } from "next/headers";
import {
  bearerAuthorizationHeader,
  findOperatorSession,
  sessionCookieName,
  updateOperatorSessionTokens,
} from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";

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
  const requestBody = request.method === "GET" ? undefined : await request.text();

  async function callUpstream(accessToken: string): Promise<Response> {
    const forwardedHeaders: Record<string, string> = {
      authorization: bearerAuthorizationHeader(accessToken),
      accept: "application/json",
    };
    if (requestBody !== undefined) forwardedHeaders["content-type"] = "application/json";
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

  return new Response(upstreamReply.body, {
    status: upstreamReply.status,
    headers: { "content-type": upstreamReply.headers.get("content-type") ?? "application/json" },
  });
}

export const GET = forwardToPlatform;
export const PUT = forwardToPlatform;
export const POST = forwardToPlatform;
export const PATCH = forwardToPlatform;
