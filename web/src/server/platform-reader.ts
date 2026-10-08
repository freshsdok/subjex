import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  bearerAuthorizationHeader,
  findOperatorSession,
  sessionCookieName,
  updateOperatorSessionTokens,
  type OperatorSession,
} from "./operator-session";
import { platformApiBase } from "./upstream";
import type { components } from "@/api/schema";

export type OperatorSelfDocument = components["schemas"]["OperatorSelfDocument"];

// Current session or go to login — 取当前会话，没有就跳登录页。
export async function requireOperatorSession(): Promise<OperatorSession & { sessionId: string }> {
  const sessionId = (await cookies()).get(sessionCookieName)?.value;
  const session = await findOperatorSession(sessionId);
  if (!session || !sessionId) redirect("/login");
  return { ...session, sessionId };
}

async function silentRefresh(
  sessionId: string,
  refreshToken: string,
): Promise<string | undefined> {
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
  const tokens = (await refreshReply.json()) as {
    accessToken?: unknown;
    refreshToken?: unknown;
    expiresIn?: unknown;
  };
  const accessToken = typeof tokens.accessToken === "string" ? tokens.accessToken : "";
  const newRefresh = typeof tokens.refreshToken === "string" ? tokens.refreshToken : "";
  const expiresIn = typeof tokens.expiresIn === "number" ? tokens.expiresIn : 0;
  if (!accessToken || !newRefresh || expiresIn <= 0) return undefined;
  await updateOperatorSessionTokens(sessionId, {
    accessToken,
    refreshToken: newRefresh,
    accessExpiresAtMillis: Date.now() + expiresIn * 1000,
  });
  return accessToken;
}

// Server-side read of a platform JSON endpoint — 服务端直接读平台 JSON 接口；401 静默刷新一次。
export async function readPlatform<Body>(platformPath: string): Promise<{ status: number; body?: Body }> {
  const session = await requireOperatorSession();
  let upstreamReply = await fetch(`${platformApiBase}/api/v1/${platformPath}`, {
    headers: {
      authorization: bearerAuthorizationHeader(session.accessToken),
      accept: "application/json",
    },
    cache: "no-store",
  });
  if (upstreamReply.status === 401) {
    const accessToken = await silentRefresh(session.sessionId, session.refreshToken);
    if (!accessToken) redirect("/login");
    upstreamReply = await fetch(`${platformApiBase}/api/v1/${platformPath}`, {
      headers: {
        authorization: bearerAuthorizationHeader(accessToken),
        accept: "application/json",
      },
      cache: "no-store",
    });
    if (upstreamReply.status === 401) redirect("/login");
  }
  if (!upstreamReply.ok) return { status: upstreamReply.status };
  return { status: upstreamReply.status, body: (await upstreamReply.json()) as Body };
}
