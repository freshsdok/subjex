import { platformApiBase } from "@/server/upstream";
import { NextResponse } from "next/server";

// Start OIDC — 开始 OIDC：向平台申请 PKCE state，再 302 到 IdP。
export async function GET(request: Request): Promise<Response> {
  const origin = consoleOrigin(request);
  let startReply: Response;
  try {
    startReply = await fetch(`${platformApiBase}/api/v1/auth/oidc/start`, {
      method: "POST",
      headers: { accept: "application/json" },
      cache: "no-store",
    });
  } catch {
    return NextResponse.redirect(new URL("/login?oidc=unreachable", origin), 302);
  }
  if (startReply.status === 404) {
    return NextResponse.redirect(new URL("/login?oidc=disabled", origin), 302);
  }
  if (!startReply.ok) {
    return NextResponse.redirect(new URL("/login?oidc=error", origin), 302);
  }
  const body = (await startReply.json()) as { authorizationUrl?: unknown };
  const authorizationUrl = typeof body.authorizationUrl === "string" ? body.authorizationUrl : "";
  if (!authorizationUrl) {
    return NextResponse.redirect(new URL("/login?oidc=error", origin), 302);
  }
  return NextResponse.redirect(authorizationUrl, 302);
}

function consoleOrigin(request: Request): string {
  const configured = process.env.CONSOLE_PUBLIC_URL?.trim() || process.env.NEXT_PUBLIC_CONSOLE_URL?.trim();
  if (configured) return configured.replace(/\/$/, "");
  return new URL(request.url).origin;
}
