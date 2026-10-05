import { cookies } from "next/headers";
import {
  closeOperatorSession,
  openOperatorSession,
  sessionCookieName,
  sessionLifetimeSeconds,
} from "@/server/operator-session";
import { basicCredentialHeader, platformApiBase } from "@/server/upstream";

// Sign in — 登录：用账号密码向平台 /api/v1/me 验证，成功后发 httpOnly 会话 cookie。
export async function POST(request: Request): Promise<Response> {
  const signInForm = (await request.json().catch(() => null)) as
    | { loginName?: unknown; password?: unknown }
    | null;
  const loginName = typeof signInForm?.loginName === "string" ? signInForm.loginName.trim() : "";
  const password = typeof signInForm?.password === "string" ? signInForm.password : "";
  if (!loginName || !password) {
    return Response.json({ reason: "missing-credentials" }, { status: 400 });
  }

  const credentialHeader = basicCredentialHeader(loginName, password);
  let upstreamReply: Response;
  try {
    upstreamReply = await fetch(`${platformApiBase}/api/v1/me`, {
      headers: { authorization: credentialHeader, accept: "application/json" },
      cache: "no-store",
    });
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }
  if (upstreamReply.status === 401) {
    return Response.json({ reason: "wrong-credentials" }, { status: 401 });
  }
  if (!upstreamReply.ok) {
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }

  const sessionId = openOperatorSession(loginName, credentialHeader);
  const cookieJar = await cookies();
  cookieJar.set(sessionCookieName, sessionId, {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: sessionLifetimeSeconds,
  });
  return Response.json(await upstreamReply.json());
}

// Sign out — 退出：删掉服务端会话并清 cookie。
export async function DELETE(): Promise<Response> {
  const cookieJar = await cookies();
  closeOperatorSession(cookieJar.get(sessionCookieName)?.value);
  cookieJar.delete(sessionCookieName);
  return new Response(null, { status: 204 });
}
