import { cookies } from "next/headers";
import {
  closeOperatorSession,
  findOperatorSession,
  openOperatorSession,
  sessionCookieName,
  sessionLifetimeSeconds,
} from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";

type TokenReply = {
  accessToken?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
  tokenType?: unknown;
};

// Sign in — 登录：调用平台 /api/v1/auth/login，会话只存加密的 access/refresh，从不存 Basic。
export async function POST(request: Request): Promise<Response> {
  const signInForm = (await request.json().catch(() => null)) as
    | { loginName?: unknown; password?: unknown }
    | null;
  const loginName = typeof signInForm?.loginName === "string" ? signInForm.loginName.trim() : "";
  const password = typeof signInForm?.password === "string" ? signInForm.password : "";
  if (!loginName || !password) {
    return Response.json({ reason: "missing-credentials" }, { status: 400 });
  }

  let tokenReply: Response;
  try {
    tokenReply = await fetch(`${platformApiBase}/api/v1/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ loginName, password }),
      cache: "no-store",
    });
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }
  if (tokenReply.status === 401) {
    return Response.json({ reason: "wrong-credentials" }, { status: 401 });
  }
  if (tokenReply.status === 403) {
    const denied = (await tokenReply.json().catch(() => ({}))) as { reason?: unknown };
    if (denied.reason === "mfa-enrollment-required") {
      return Response.json({ reason: "mfa-enrollment-required" }, { status: 403 });
    }
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }
  if (!tokenReply.ok) {
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }

  const tokens = (await tokenReply.json()) as TokenReply & {
    mfaRequired?: unknown;
    mfaToken?: unknown;
  };
  if (tokens.mfaRequired === true) {
    const mfaToken = typeof tokens.mfaToken === "string" ? tokens.mfaToken : "";
    const expiresIn = typeof tokens.expiresIn === "number" ? tokens.expiresIn : 0;
    if (!mfaToken || expiresIn <= 0) {
      return Response.json({ reason: "platform-error" }, { status: 502 });
    }
    // No session cookie yet — client must POST /api/session/mfa with the code.
    // 尚未写会话 cookie；客户端需带验证码 POST /api/session/mfa。
    return Response.json({ mfaRequired: true, mfaToken, expiresIn });
  }
  const accessToken = typeof tokens.accessToken === "string" ? tokens.accessToken : "";
  const refreshToken = typeof tokens.refreshToken === "string" ? tokens.refreshToken : "";
  const expiresIn = typeof tokens.expiresIn === "number" ? tokens.expiresIn : 0;
  if (!accessToken || !refreshToken || expiresIn <= 0) {
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }

  let meReply: Response;
  try {
    meReply = await fetch(`${platformApiBase}/api/v1/me`, {
      headers: { authorization: `Bearer ${accessToken}`, accept: "application/json" },
      cache: "no-store",
    });
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }
  if (!meReply.ok) {
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }

  const sessionId = await openOperatorSession({
    loginName,
    accessToken,
    refreshToken,
    accessExpiresAtMillis: Date.now() + expiresIn * 1000,
  });
  const cookieJar = await cookies();
  cookieJar.set(sessionCookieName, sessionId, {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: sessionLifetimeSeconds,
  });
  return Response.json(await meReply.json());
}

// Sign out — 退出：吊销平台刷新族、删掉服务端会话并清 cookie。
export async function DELETE(): Promise<Response> {
  const cookieJar = await cookies();
  const sessionId = cookieJar.get(sessionCookieName)?.value;
  const session = await findOperatorSession(sessionId);
  if (session?.refreshToken) {
    try {
      await fetch(`${platformApiBase}/api/v1/auth/logout`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
        cache: "no-store",
      });
    } catch {
      // Session cookie is still cleared below even if revoke is unreachable.
    }
  }
  await closeOperatorSession(sessionId);
  cookieJar.delete(sessionCookieName);
  return new Response(null, { status: 204 });
}
