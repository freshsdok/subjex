/**
 * Shell path helpers — safe in-app login return paths and tabBar routing.
 * 终端壳路径辅助：登录后只允许回到壳内页面，并按 tabBar 规则选择跳转方式。
 */

const LOGIN_PATH = "/pages/login/login";
const HOME_PATH = "/pages/index/index";

/**
 * TabBar pages (WeChat allows at most 5). These must use {@code switchTab}, not {@code navigateTo}.
 * 底部标签页（微信最多 5 个）。必须用 {@code switchTab}，不能用 {@code navigateTo}。
 */
export const TAB_PATHS = [
  "/pages/index/index",
  "/pages/inbox/inbox",
  "/pages/questionnaire/list",
  "/pages/files/files",
  "/pages/profile/profile",
] as const;

export type TabPath = (typeof TAB_PATHS)[number];

/**
 * A shell route safe to open after sign-in. Rejects schemes, parent paths, and the login page itself.
 * 登录后可打开的壳内路径。拒绝协议、上级路径，以及登录页自身（避免循环）。
 */
export function safeShellPath(raw?: string | null): string {
  const value = (raw || "").trim();
  if (!value.startsWith("/pages/")) return "";
  if (value.includes("://") || value.includes("..") || value.includes("\\")) return "";
  const pathOnly = value.split("#")[0].split("?")[0];
  if (pathOnly === LOGIN_PATH) return "";
  // Disallow protocol-relative or extra slashes after /pages
  // 不允许协议相对地址，也不允许 /pages 后出现多余斜杠。
  if (pathOnly.includes("//")) return "";
  return value.split("#")[0];
}

/** Path without query or hash — 去掉查询串和锚点后的路径. */
export function shellPathOnly(raw?: string | null): string {
  const value = (raw || "").trim();
  if (!value) return "";
  const withSlash = value.startsWith("/") ? value : `/${value}`;
  return withSlash.split("#")[0].split("?")[0];
}

/** Whether the path is a tabBar root page — 是否为底部标签根页面. */
export function isTabPath(raw?: string | null): boolean {
  const pathOnly = shellPathOnly(raw);
  return (TAB_PATHS as readonly string[]).includes(pathOnly);
}

/** Login page URL, optionally returning to a shell page after sign-in. / 登录页地址，可在登录后回到原壳页. */
export function loginPageUrl(returnPath?: string | null): string {
  const safe = safeShellPath(returnPath);
  if (!safe) return LOGIN_PATH;
  return `${LOGIN_PATH}?redirect=${encodeURIComponent(safe)}`;
}

/** Where to go after a successful sign-in. / 登录成功后的落地页. */
export function postLoginUrl(redirect?: string | null): string {
  return safeShellPath(redirect) || HOME_PATH;
}

/**
 * Open a shell page with the API tabBar allows: switchTab for tabs, reLaunch otherwise.
 * 按 tabBar 规则打开壳页：标签页用 switchTab，其它页用 reLaunch。
 * switchTab cannot carry a query string, so tab targets are opened by path only.
 * switchTab 不能带查询串，因此标签页只按路径打开。
 */
export function openShellPage(raw?: string | null): void {
  const url = postLoginUrl(raw);
  const pathOnly = shellPathOnly(url);
  if (isTabPath(pathOnly)) {
    uni.switchTab({ url: pathOnly });
    return;
  }
  uni.reLaunch({ url });
}

export { HOME_PATH, LOGIN_PATH };
