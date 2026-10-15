import { platformApiBase } from "@/server/upstream";

/**
 * IdP providers probe — anonymous list for login page (local / generic-oidc / wechat).
 * 登录页匿名探测可用登录方式。
 */
export async function GET(): Promise<Response> {
  try {
    const reply = await fetch(`${platformApiBase}/api/v1/auth/idp/providers`, {
      headers: { accept: "application/json" },
      cache: "no-store",
    });
    if (!reply.ok) {
      return Response.json({ providers: [{ id: "local", type: "password", enabled: true }] });
    }
    return new Response(await reply.text(), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  } catch {
    return Response.json({ providers: [{ id: "local", type: "password", enabled: true }] });
  }
}
