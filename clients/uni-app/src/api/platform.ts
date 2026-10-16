/**
 * Thin platform HTTP wrapper for end-user clients.
 * 终端客户端用的薄平台 HTTP 封装。
 *
 * Auth: Bearer / OIDC OP tokens (shape TBD). Do NOT reuse the operator
 * console Redis / httpOnly session model in `web/`.
 * 认证：Bearer / OP 令牌（形态待定）。不要复用 `web/` 操作员 Redis / httpOnly 会话。
 */

const DEFAULT_BASE = "http://127.0.0.1:8080";

/** Resolve API base from Vite env or default. / 从 Vite 环境变量或默认值取 API 根。 */
export function apiBase(): string {
  const fromEnv =
    typeof import.meta !== "undefined" &&
    (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_SUBJEX_API_BASE;
  return (fromEnv && fromEnv.trim()) || DEFAULT_BASE;
}

export type PlatformRequestOptions = {
  method?: string;
  path: string;
  /** Optional Bearer access token — end-user auth, not operator Basic. */
  bearer?: string | null;
  body?: unknown;
  headers?: Record<string, string>;
};

/**
 * Fetch JSON from platform-app. Throws on non-2xx with status text.
 * 向 platform-app 取 JSON；非 2xx 抛错。
 */
export async function platformFetch<T = unknown>(
  options: PlatformRequestOptions
): Promise<T> {
  const method = (options.method || "GET").toUpperCase();
  const url = `${apiBase().replace(/\/$/, "")}${options.path.startsWith("/") ? "" : "/"}${options.path}`;
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(options.headers || {}),
  };
  if (options.bearer) {
    headers.Authorization = `Bearer ${options.bearer}`;
  }
  let body: string | undefined;
  if (options.body !== undefined && method !== "GET" && method !== "HEAD") {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  const res = await fetch(url, { method, headers, body });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`platform ${method} ${options.path} → ${res.status}: ${text || res.statusText}`);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

/**
 * Probe liveness (no auth). Useful for shell smoke.
 * 存活探针（无需认证），供壳页冒烟。
 */
export async function probeLiveness(): Promise<{ status?: string } | string> {
  const url = `${apiBase().replace(/\/$/, "")}/actuator/health/liveness`;
  const res = await fetch(url, { method: "GET", headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`liveness → ${res.status}`);
  }
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("json")) {
    return (await res.json()) as { status?: string };
  }
  return await res.text();
}
