import { platformApiBase } from "@/server/upstream";

// OIDC status — 是否已启用 SSO（供登录页显示按钮）。
export async function GET(): Promise<Response> {
  try {
    const reply = await fetch(`${platformApiBase}/api/v1/auth/oidc/status`, {
      headers: { accept: "application/json" },
      cache: "no-store",
    });
    if (!reply.ok) {
      return Response.json({ enabled: false });
    }
    const body = (await reply.json()) as { enabled?: unknown };
    return Response.json({ enabled: body.enabled === true });
  } catch {
    return Response.json({ enabled: false });
  }
}
