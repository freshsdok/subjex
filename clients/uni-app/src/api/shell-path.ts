/**
 * Shell path helpers — safe in-app login return paths for the end-user client.
 * 终端壳路径辅助：登录后只允许回到壳内页面，避免跳到外部地址。
 */

const LOGIN_PATH = "/pages/login/login";
const HOME_PATH = "/pages/index/index";

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

export { HOME_PATH, LOGIN_PATH };
