/**
 * Thin platform HTTP wrapper for end-user clients (WP2-6 / WP7-3).
 * 终端客户端用的薄平台 HTTP 封装。
 *
 * Auth: end-user Bearer ({@code eu.at.}). Do NOT reuse the operator
 * console Redis / httpOnly session model in {@code web/}, and do not call
 * operator {@code /api/v1/auth/**} from this shell.
 * 认证：终端 Bearer。不要复用 web/ 操作员会话，也不要从本壳调用操作员认证接口。
 * Uses {@code uni.request} so H5 and the WeChat mini-program share one path.
 * 使用 uni.request，H5 与微信小程序同一条请求路径。
 */

import { readBearer } from "./auth-token";
import { loginPageUrl } from "./shell-path";
import { TENANT_HEADER, normalizeBusinessTenantId, readTenantId } from "./tenant";

const DEFAULT_BASE = "http://127.0.0.1:8080";

/** Resolve API base from Vite env or default. / 从 Vite 环境变量或默认值取 API 根。 */
export function apiBase(): string {
  const fromEnv =
    typeof import.meta !== "undefined" &&
    (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_SUBJEX_API_BASE;
  return (fromEnv && fromEnv.trim()) || DEFAULT_BASE;
}

/** HTTP error with optional server {@code reason} — 带服务端 reason 的 HTTP 错误。 */
export class PlatformHttpError extends Error {
  readonly status: number;
  readonly reason?: string;
  readonly bodyText: string;

  constructor(method: string, path: string, status: number, statusText: string, bodyText: string) {
    const reason = parseReason(bodyText);
    const detail = reason ? `reason=${reason}` : bodyText || statusText;
    super(`platform ${method} ${path} → ${status}: ${detail}`);
    this.name = "PlatformHttpError";
    this.status = status;
    this.reason = reason;
    this.bodyText = bodyText;
  }
}

function parseReason(bodyText: string): string | undefined {
  if (!bodyText) return undefined;
  try {
    const parsed = JSON.parse(bodyText) as { reason?: unknown };
    return typeof parsed.reason === "string" && parsed.reason.trim()
      ? parsed.reason.trim()
      : undefined;
  } catch {
    return undefined;
  }
}

export type PlatformRequestOptions = {
  method?: string;
  path: string;
  /** Optional Bearer access token — end-user auth, not operator Basic. */
  bearer?: string | null;
  body?: unknown;
  headers?: Record<string, string>;
  /**
   * Optional tenant. When true, attach {@code X-Tenant-Id} from stored context;
   * when a string, attach that id. Login also sends JSON {@code tenantId}.
   * 可选租户：true 用已存上下文；字符串则用该值。登录同时在 JSON 里带 tenantId。
   */
  tenantId?: string | true;
  /** Do not try refresh+retry on 401 (login/refresh/logout). / 401 时不刷新重试（登录/刷新/退出）。 */
  skipAuthRetry?: boolean;
  /**
   * Raw octet-stream body (chunked upload). Not JSON-serialized.
   * 原始分片字节，不按 JSON 序列化。
   */
  rawBody?: ArrayBuffer;
};

type SessionRefresh = () => Promise<unknown>;
let sessionRefresh: SessionRefresh | null = null;

/** Wire {@code refreshSession} without an import cycle — 接入刷新且避免循环依赖. */
export function registerSessionRefresh(fn: SessionRefresh): void {
  sessionRefresh = fn;
}

/** Friendly Chinese copy for 401/403/409/413/415 — 给最终用户看的中文说明. */
export function messageForHttpError(err: unknown): string {
  if (err instanceof PlatformHttpError) {
    if (err.status === 401) return "登录已过期，请重新登录";
    if (err.status === 403) {
      if (err.reason === "end-user-tenant-access-denied") {
        return "当前账号无法访问该组织的内容";
      }
      return "没有权限完成这项操作";
    }
    if (err.status === 404) return "没有找到相关内容";
    if (err.status === 409) {
      if (err.reason === "last-authenticator") {
        return "这是您目前唯一的登录方式，解绑后将无法登录。请先绑定其他方式。";
      }
      return "您已经提交过这份问卷了";
    }
    if (err.status === 413) return "文件太大了，请选择不超过 50MB 的文件";
    if (err.status === 415) return "这种文件类型暂不支持，请改用图片、PDF 或常用办公文档";
    return "操作没有成功，请稍后重试";
  }
  if (err instanceof Error && err.message) {
    const zh = err.message.split(" / ")[0].trim();
    return zh || "出了一点问题，请稍后重试";
  }
  return "出了一点问题，请稍后重试";
}

/** Show a short toast (no engineer jargon) — 弹出简短提示（不用术语）. */
export function toastHttpError(err: unknown): void {
  uni.showToast({ title: messageForHttpError(err), icon: "none", duration: 2500 });
}

/**
 * Send the user to the login page, remembering the current shell page when possible.
 * 回到登录页；如能确定当前壳页，登录成功后返回那里。
 */
export function goToLogin(returnPath?: string): void {
  let path = (returnPath || "").trim();
  if (!path) {
    try {
      const pages = getCurrentPages();
      const current = pages[pages.length - 1] as { route?: string } | undefined;
      const route = (current?.route || "").trim();
      if (route && route !== "pages/login/login") {
        path = route.startsWith("/") ? route : `/${route}`;
      }
    } catch {
      path = "";
    }
  }
  uni.reLaunch({ url: loginPageUrl(path) });
}

async function tryRefreshSession(): Promise<boolean> {
  if (!sessionRefresh) return false;
  try {
    await sessionRefresh();
    return true;
  } catch {
    return false;
  }
}

function sendRequest(
  method: string,
  url: string,
  headers: Record<string, string>,
  body?: string | ArrayBuffer
): Promise<{ status: number; statusText: string; text: string }> {
  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method: method as UniNamespace.RequestOptions["method"],
      header: headers,
      data: body,
      dataType: "text",
      responseType: "text",
      success: (res) => {
        const data = res.data;
        const text = typeof data === "string" ? data : data == null ? "" : JSON.stringify(data);
        resolve({ status: res.statusCode || 0, statusText: "", text });
      },
      fail: (err) => {
        reject(new Error((err && err.errMsg) || "网络连接失败，请稍后重试"));
      },
    });
  });
}

/**
 * Fetch JSON from platform-app. Throws {@link PlatformHttpError} on non-2xx.
 * 向 platform-app 取 JSON；非 2xx 抛 {@link PlatformHttpError}。
 * On 401, try one refresh then retry; if that fails, go to login.
 * 遇到 401 会先尝试刷新再重试一次；仍失败则进入登录页。
 */
export async function platformFetch<T = unknown>(options: PlatformRequestOptions): Promise<T> {
  const method = (options.method || "GET").toUpperCase();
  const url = `${apiBase().replace(/\/$/, "")}${options.path.startsWith("/") ? "" : "/"}${options.path}`;
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(options.headers || {}),
  };
  if (options.bearer) {
    headers.Authorization = `Bearer ${options.bearer}`;
  }
  const tenant =
    options.tenantId === true ? readTenantId() : options.tenantId ? options.tenantId.trim() : "";
  if (tenant && !headers[TENANT_HEADER]) {
    headers[TENANT_HEADER] = tenant;
  }
  let body: string | ArrayBuffer | undefined;
  if (options.rawBody !== undefined && method !== "GET" && method !== "HEAD") {
    if (!headers["Content-Type"]) {
      headers["Content-Type"] = "application/octet-stream";
    }
    body = options.rawBody;
  } else if (options.body !== undefined && method !== "GET" && method !== "HEAD") {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  const res = await sendRequest(method, url, headers, body);
  if (!res.status || res.status < 200 || res.status >= 300) {
    const err = new PlatformHttpError(method, options.path, res.status, res.statusText, res.text);
    if (err.status === 401 && !options.skipAuthRetry) {
      const refreshed = await tryRefreshSession();
      if (refreshed) {
        const nextBearer = readBearer() || options.bearer;
        return platformFetch<T>({ ...options, bearer: nextBearer, skipAuthRetry: true });
      }
      goToLogin();
    }
    throw err;
  }
  if (res.status === 204 || !res.text) {
    return undefined as T;
  }
  return JSON.parse(res.text) as T;
}

/**
 * Authenticated JSON call: attach stored Bearer + tenant, require sign-in.
 * 已登录 JSON 调用：带上令牌和租户；未登录则去登录页。
 */

/**
 * If this API request Host is a bound tenant domain, return that tenant id.
 * Does nothing useful when the API base is a reserved platform host (including 127.0.0.1):
 * the page hostname cannot be sent as Host to a different API origin.
 * 若本次 API 请求的 Host 是已绑定的租户域名，则返回该租户。
 * 当 API 根地址是平台保留主机（包括 127.0.0.1）时不会发现租户：浏览器不能向另一个源改写 Host。
 */
export async function discoverTenantFromApiHost(): Promise<string | null> {
  try {
    const body = await platformFetch<{ bound?: boolean; tenantId?: string }>({
      path: "/api/v1/public/host-tenant",
      skipAuthRetry: true,
    });
    if (body && body.bound === true) {
      return normalizeBusinessTenantId(body.tenantId);
    }
  } catch {
    return null;
  }
  return null;
}

export async function authedFetch<T = unknown>(
  options: Omit<PlatformRequestOptions, "bearer">
): Promise<T> {
  const bearer = readBearer();
  if (!bearer) {
    goToLogin();
    throw new Error("请先登录");
  }
  return platformFetch<T>({
    ...options,
    bearer,
    tenantId: options.tenantId ?? true,
  });
}

/**
 * Probe liveness (no auth). Useful for shell smoke.
 * 存活探针（无需认证），供壳页冒烟。
 */
export async function probeLiveness(): Promise<{ status?: string } | string> {
  const url = `${apiBase().replace(/\/$/, "")}/actuator/health/liveness`;
  const res = await sendRequest("GET", url, { Accept: "application/json" });
  if (!res.status || res.status < 200 || res.status >= 300) {
    throw new Error("暂时无法连接服务，请稍后重试");
  }
  const text = res.text || "";
  if (text.trim().startsWith("{")) {
    return JSON.parse(text) as { status?: string };
  }
  return text;
}
