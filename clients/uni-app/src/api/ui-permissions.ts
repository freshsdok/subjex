/**
 * UI permission Manifest client for uni-app shell (ADR 0010 / PC-5).
 * 终端壳 UI 权限 Manifest 客户端（ADR 0010 / PC-5）。
 *
 * Gate UX only — backend still enforces. Locked menu = grey; missing button = disable.
 * 仅前端体验；后端仍强制。菜单置灰；按钮禁用。
 */

import { platformFetch } from "./platform";

/** Manifest client id for this project — 本工程的 Manifest client id。 */
export const UI_PERMISSION_CLIENT = "uni-app";

/** Known shell button ids (classpath uni-app-ui.yaml) — 壳已知按钮 id。 */
export const BUTTON_SHELL_REPROBE = "btn.shell.reprobe";
export const BUTTON_SHELL_WX_LOGIN_STUB = "btn.shell.wxLoginStub";

export type UiPermissionEntry = {
  id: string;
  kind: string;
  href?: string | null;
  route?: string | null;
  permission?: string | null;
  anyOf?: string[] | null;
  allowed: boolean;
};

export type UiPermissionManifest = {
  version: number;
  client: string;
  defaults?: {
    lockedMenuEffect?: string;
    missingButtonEffect?: string;
  };
  entries: UiPermissionEntry[];
};

/** Local labels for shell menu ids — 壳菜单 id 的本地文案。 */
export const SHELL_MENU_LABEL_BY_ID: Readonly<Record<string, string>> = {
  "nav.home": "首页 / Home",
  "nav.login": "登录 / Sign in",
  "nav.inbox": "站内信 / Inbox",
};

/**
 * GET /api/v1/ui-permissions?client=uni-app (auth required).
 * 拉取终端壳 Manifest（需登录）。
 */
export async function fetchUiPermissions(
  bearer?: string | null
): Promise<UiPermissionManifest> {
  return platformFetch<UiPermissionManifest>({
    path: `/api/v1/ui-permissions?client=${encodeURIComponent(UI_PERMISSION_CLIENT)}`,
    bearer: bearer || undefined,
  });
}

/** Menu entries only — 仅菜单条目。 */
export function menuEntries(manifest: UiPermissionManifest | undefined): UiPermissionEntry[] {
  return (manifest?.entries ?? []).filter((e) => e.kind === "menu");
}

/**
 * Whether a button is allowed; missing entry → {@code fallback}.
 * 按钮是否允许；Manifest 无该 id 时用 fallback。
 */
export function buttonAllowed(
  manifest: UiPermissionManifest | undefined,
  buttonId: string,
  fallback: boolean
): boolean {
  const entry = (manifest?.entries ?? []).find(
    (e) => e.kind === "button" && e.id === buttonId
  );
  if (!entry) return fallback;
  return Boolean(entry.allowed);
}

/** Hint text for locked UX — 置灰/禁用时的权限提示。 */
export function missingPermissionHint(entry: UiPermissionEntry): string {
  const anyOf = (entry.anyOf ?? []).map((p) => p.trim()).filter(Boolean);
  if (anyOf.length > 0) return anyOf.join(" | ");
  return (entry.permission ?? "").trim();
}
