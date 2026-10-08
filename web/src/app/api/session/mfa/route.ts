import { cookies } from "next/headers";
import {
  openOperatorSession,
  sessionCookieName,
  sessionLifetimeSeconds,
} from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";

type TokenReply = {
  accessToken?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
};

// Complete MFA after password challenge — 口令挑战后的 MFA 校验：签发会话。
export async function POST(request: Request): Promise<Response> {
  const body = (await request.json().catch(() => null)) as
    | { mfaToken?: unknown; code?: unknown }
    | null;
  const mfaToken = typeof body?.mfaToken === "string" ? body.mfaToken.trim() : "";
  const code = typeof body?.code === "string" ? body.code.trim() : "";
  if (!mfaToken || !code) {
    return Response.json({ reason: "missing-mfa" }, { status: 400 });
  }

  let tokenReply: Response;
  try {
    tokenReply = await fetch(`${platformApiBase}/api/v1/auth/mfa/verify`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ mfaToken, code }),
      cache: "no-store",
    });
  } catch {
    return Response.json({ reason: "platform-unreachable" }, { status: 502 });
  }
  if (tokenReply.status === 401) {
    return Response.json({ reason: "invalid-mfa" }, { status: 401 });
  }
  if (!tokenReply.ok) {
    return Response.json({ reason: "platform-error" }, { status: 502 });
  }

  const tokens = (await tokenReply.json()) as TokenReply;
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
  const me = (await meReply.json()) as { loginName?: unknown };
  const loginName = typeof me.loginName === "string" ? me.loginName : "";
  if (!loginName) {
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
  return Response.json(me);
}
