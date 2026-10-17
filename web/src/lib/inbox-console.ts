import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { orgTenantCookieName, parseOrgTenantCookie } from "@/lib/org-console";

/**
 * Inbox console helpers — unread poll interval + tenant cookie resolve (IM-5).
 * 站内信控制台辅助：未读轮询间隔与租户 cookie 解析（IM-5）。
 */

/** Locked ADR default: ~60s console unread poll — 锁定默认：控制台未读约 60s 轮询. */
export const INBOX_UNREAD_POLL_MS = 60_000;

/**
 * Resolve tenant id from a cookie header string (browser {@code document.cookie} or test).
 * Prefer org tenant, then declaration tenant (same order as /inbox page).
 * 从 cookie 串解析租户：先组织租户，再声明租户（与 /inbox 页一致）。
 */
export function resolveInboxTenantFromCookieHeader(cookieHeader: string): string {
  const map = parseCookieHeader(cookieHeader);
  const fromOrg = parseOrgTenantCookie(map.get(orgTenantCookieName));
  if (fromOrg) return fromOrg;
  return parseDeclarationTenantCookie(map.get(declarationTenantCookieName));
}

/**
 * Prefer live cookie, else server-provided initial hint — 优先实时 cookie，否则用服务端初值.
 */
export function resolveInboxTenantId(initialTenantId: string, cookieHeader: string): string {
  const fromCookie = resolveInboxTenantFromCookieHeader(cookieHeader);
  if (fromCookie) return fromCookie;
  return (initialTenantId ?? "").trim();
}

/** Display cap for badge numerals (99+) — 角标数字上限（99+）. */
export function formatInboxUnreadCount(count: number): string {
  if (count <= 0) return "";
  if (count > 99) return "99+";
  return String(count);
}

function parseCookieHeader(raw: string): Map<string, string> {
  const map = new Map<string, string>();
  if (!raw) return map;
  for (const part of raw.split(";")) {
    const idx = part.indexOf("=");
    if (idx <= 0) continue;
    const name = part.slice(0, idx).trim();
    const value = part.slice(idx + 1).trim();
    if (name) map.set(name, value);
  }
  return map;
}
