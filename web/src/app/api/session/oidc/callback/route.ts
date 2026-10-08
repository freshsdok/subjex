import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import {
  openOperatorSession,
  sessionCookieName,
  sessionLifetimeSeconds,
} from "@/server/operator-session";
import { platformApiBase } from "@/server/upstream";

// OIDC callback — IdP 回跳：用 code+state 向平台换 Bearer，写入控制台会话。
export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const idpError = url.searchParams.get("error");
  if (idpError) {
    return NextResponse.redirect(new URL("/login?oidc=denied", consoleOrigin(request)), 302);
  }
  const code = url.searchParams.get("code") ?? "";
  const state = url.searchParams.get("state") ?? "";
  if (!code || !state) {
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
  }

  let tokenReply: Response;
  try {
    tokenReply = await fetch(`${platformApiBase}/api/v1/auth/oidc/callback`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ code, state }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.redirect(new URL("/login?oidc=unreachable", consoleOrigin(request)), 302);
  }
  if (tokenReply.status === 403) {
    const denied = (await tokenReply.json().catch(() => ({}))) as { reason?: unknown };
    if (denied.reason === "oidc-unlinked") {
      return NextResponse.redirect(new URL("/login?oidc=unlinked", consoleOrigin(request)), 302);
    }
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
  }
  if (tokenReply.status === 404) {
    return NextResponse.redirect(new URL("/login?oidc=disabled", consoleOrigin(request)), 302);
  }
  if (!tokenReply.ok) {
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
  }

  const tokens = (await tokenReply.json()) as {
    accessToken?: unknown;
    refreshToken?: unknown;
    expiresIn?: unknown;
  };
  const accessToken = typeof tokens.accessToken === "string" ? tokens.accessToken : "";
  const refreshToken = typeof tokens.refreshToken === "string" ? tokens.refreshToken : "";
  const expiresIn = typeof tokens.expiresIn === "number" ? tokens.expiresIn : 0;
  if (!accessToken || !refreshToken || expiresIn <= 0) {
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
  }

  let meReply: Response;
  try {
    meReply = await fetch(`${platformApiBase}/api/v1/me`, {
      headers: { authorization: `Bearer ${accessToken}`, accept: "application/json" },
      cache: "no-store",
    });
  } catch {
    return NextResponse.redirect(new URL("/login?oidc=unreachable", consoleOrigin(request)), 302);
  }
  if (!meReply.ok) {
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
  }
  const me = (await meReply.json()) as { loginName?: unknown };
  const loginName = typeof me.loginName === "string" ? me.loginName : "";
  if (!loginName) {
    return NextResponse.redirect(new URL("/login?oidc=error", consoleOrigin(request)), 302);
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
  return NextResponse.redirect(new URL("/", consoleOrigin(request)), 302);
}

function consoleOrigin(request: Request): string {
  const configured = process.env.CONSOLE_PUBLIC_URL?.trim() || process.env.NEXT_PUBLIC_CONSOLE_URL?.trim();
  if (configured) return configured.replace(/\/$/, "");
  return new URL(request.url).origin;
}
