import { cookies } from "next/headers";
import { findOperatorSession, sessionCookieName } from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";

// Platform proxy — 平台代理：把 /api/platform/<路径> 转发到平台 /api/v1/<路径>，
// 由服务端带上会话里的认证头；浏览器永远拿不到账号密码。
async function forwardToPlatform(
  request: Request,
  context: { params: Promise<{ segments: string[] }> },
): Promise<Response> {
  const cookieJar = await cookies();
  const session = findOperatorSession(cookieJar.get(sessionCookieName)?.value);
  if (!session) {
    return Response.json({ reason: "signed-out" }, { status: 401 });
  }

  const { segments } = await context.params;
  const platformPath = segments.map(encodeURIComponent).join("/");
  const query = new URL(request.url).search;
  const forwardedHeaders: Record<string, string> = {
    authorization: session.credentialHeader,
    accept: "application/json",
  };
  const requestBody = request.method === "GET" ? undefined : await request.text();
  if (requestBody !== undefined) forwardedHeaders["content-type"] = "application/json";

  let upstreamReply: Response;
  try {
    upstreamReply = await fetch(`${platformApiBase}/api/v1/${platformPath}${query}`, {
      method: request.method,
      headers: forwardedHeaders,
      body: requestBody,
      cache: "no-store",
    });
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }
  return new Response(upstreamReply.body, {
    status: upstreamReply.status,
    headers: { "content-type": upstreamReply.headers.get("content-type") ?? "application/json" },
  });
}

export const GET = forwardToPlatform;
export const PUT = forwardToPlatform;
export const POST = forwardToPlatform;
