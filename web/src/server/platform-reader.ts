import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { findOperatorSession, sessionCookieName, type OperatorSession } from "./operator-session";
import { platformApiBase } from "./upstream";
import type { components } from "@/api/schema";

export type OperatorSelfDocument = components["schemas"]["OperatorSelfDocument"];

// Current session or go to login — 取当前会话，没有就跳登录页。
export async function requireOperatorSession(): Promise<OperatorSession> {
  const session = findOperatorSession((await cookies()).get(sessionCookieName)?.value);
  if (!session) redirect("/login");
  return session;
}

// Server-side read of a platform JSON endpoint — 服务端直接读平台 JSON 接口；401 视为会话失效。
export async function readPlatform<Body>(platformPath: string): Promise<{ status: number; body?: Body }> {
  const session = await requireOperatorSession();
  const upstreamReply = await fetch(`${platformApiBase}/api/v1/${platformPath}`, {
    headers: { authorization: session.credentialHeader, accept: "application/json" },
    cache: "no-store",
  });
  if (upstreamReply.status === 401) redirect("/login");
  if (!upstreamReply.ok) return { status: upstreamReply.status };
  return { status: upstreamReply.status, body: (await upstreamReply.json()) as Body };
}
