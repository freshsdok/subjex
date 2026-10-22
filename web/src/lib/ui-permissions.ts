import type { PhraseBook } from "@/i18n/phrases";

/**
 * UI permission Manifest client types (ADR 0010 / PC-3) — UI 权限 Manifest 客户端类型。
 * <p>
 * Gate source is {@code GET /api/v1/ui-permissions}; labels stay in phrases via id→labelKey map
 * (YAML has no labelKey). {@link consoleSections} remains label/fallback catalog for one release.
 * 门禁真源为 GET ui-permissions；文案仍走 phrases（YAML 无 labelKey）。console-sections 作标签/回退。
 */

/** Fallback section shape (console-sections) — 回退栏目形状（console-sections）。 */
export type ConsoleNavFallbackSection = {
  href: string;
  labelKey: keyof PhraseBook;
  requiredPermission: string | null;
  requiredAnyOf?: readonly string[];
};

/** One Manifest entry from the API — API 返回的一条绑定。 */
export type UiPermissionEntryDocument = {
  id: string;
  kind: string;
  href?: string | null;
  route?: string | null;
  permission?: string | null;
  anyOf?: string[] | null;
  allowed: boolean;
};

/** Manifest document — Manifest 文档。 */
export type UiPermissionManifestDocument = {
  version: number;
  client: string;
  defaults?: {
    lockedMenuEffect?: string;
    missingButtonEffect?: string;
  };
  entries: UiPermissionEntryDocument[];
};

/** Resolved left-nav row for the console shell — 控制台侧栏解析后的一行。 */
export type ConsoleNavItem = {
  id: string;
  href: string;
  labelKey: keyof PhraseBook;
  allowed: boolean;
  /** Permission names shown in locked tooltip — 置灰 title 中展示的权限名。 */
  missingPermissionHint: string;
};

/**
 * Manifest entry id → phrase key (console menus from ui-authz/console-ui.yaml).
 * YAML 条目 id → 文案键（与 classpath Manifest 菜单 id 对齐）。
 */
export const CONSOLE_MENU_LABEL_BY_ID: Readonly<Record<string, keyof PhraseBook>> = {
  "nav.overview": "navOverview",
  "nav.services": "navServices",
  "nav.config": "navConfig",
  "nav.flags": "navFlags",
  "nav.inbox": "navInbox",
  "nav.inbox.compose": "navInboxCompose",
  "nav.inbox.groups": "navInboxGroups",
  "nav.audit": "navAudit",
  "nav.dead-letters": "navDeadLetters",
  "nav.deploy": "navDeploy",
  "nav.forms": "navForms",
  "nav.pages": "navPages",
  "nav.search": "navSearch",
  "nav.declarations": "navDeclarations",
  "nav.codegen": "navCodegen",
  "nav.operators": "navOperators",
  "nav.tenants": "navTenants",
  "nav.org": "navOrg",
  "nav.capabilities": "navCapabilities",
  "nav.ui-authz": "navUiAuthz",
};

/** Tooltip text for a locked entry — 置灰条目的权限提示文案。 */
export function missingPermissionHint(entry: UiPermissionEntryDocument): string {
  const anyOf = (entry.anyOf ?? []).map((p) => p.trim()).filter(Boolean);
  if (anyOf.length > 0) return anyOf.join(" | ");
  const permission = (entry.permission ?? "").trim();
  return permission;
}

/**
 * Resolve phrase key for a menu entry: prefer id map, else href from fallback sections.
 * 解析文案键：优先 id 映射，否则用回退栏目的 href。
 */
export function resolveConsoleMenuLabelKey(
  entryId: string,
  href: string,
  fallbackSections: readonly ConsoleNavFallbackSection[],
): keyof PhraseBook | undefined {
  const byId = CONSOLE_MENU_LABEL_BY_ID[entryId];
  if (byId) return byId;
  const byHref = fallbackSections.find((s) => s.href === href);
  return byHref?.labelKey;
}

/**
 * Build nav rows from Manifest menus; empty/invalid Manifest → fallback sections + local gate.
 * 从 Manifest 菜单构建侧栏；Manifest 无效则回退 console-sections + 本地权限判定。
 */
export function buildConsoleNavItems(
  manifest: UiPermissionManifestDocument | undefined,
  fallbackSections: readonly ConsoleNavFallbackSection[],
  grantedPermissions: ReadonlySet<string>,
): ConsoleNavItem[] {
  const menus = (manifest?.entries ?? []).filter(
    (e) => e.kind === "menu" && typeof e.href === "string" && e.href.length > 0,
  );
  if (menus.length > 0) {
    const items: ConsoleNavItem[] = [];
    for (const entry of menus) {
      const href = entry.href as string;
      const labelKey = resolveConsoleMenuLabelKey(entry.id, href, fallbackSections);
      if (!labelKey) {
        // Skip unknown labels rather than render blank — 无文案键则跳过，避免空白栏。
        continue;
      }
      items.push({
        id: entry.id,
        href,
        labelKey,
        allowed: Boolean(entry.allowed),
        missingPermissionHint: missingPermissionHint(entry),
      });
    }
    if (items.length > 0) return items;
  }
  return fallbackSections.map((section) => {
    const allowed = section.requiredAnyOf
      ? section.requiredAnyOf.some((p) => grantedPermissions.has(p))
      : section.requiredPermission === null || grantedPermissions.has(section.requiredPermission);
    const hint = section.requiredAnyOf
      ? section.requiredAnyOf.join(" | ")
      : (section.requiredPermission ?? "");
    return {
      id: `fallback:${section.href}`,
      href: section.href,
      labelKey: section.labelKey,
      allowed,
      missingPermissionHint: hint,
    };
  });
}

/** Known console button ids (PC-4 / #5) — 控制台已知按钮 id。 */
export const BUTTON_INBOX_COMPOSE_SEND = "btn.inbox.compose.send";
export const BUTTON_ORG_WRITE = "btn.org.write";
export const BUTTON_FLAGS_WRITE = "btn.flags.write";
export const BUTTON_UI_AUTHZ_WRITE = "btn.ui-authz.write";
export const BUTTON_UI_AUTHZ_PROMOTE = "btn.ui-authz.promote";
export const BUTTON_DECLARATIONS_WRITE = "btn.declarations.write";
export const BUTTON_DECLARATIONS_PROMOTE = "btn.declarations.promote";
export const BUTTON_TENANTS_MANAGE = "btn.tenants.manage";
export const BUTTON_CONFIG_WRITE = "btn.config.write";
export const BUTTON_OPERATORS_MANAGE = "btn.operators.manage";

/** Known console page ids for ForbiddenNotice (#5 / deepen-2/3) — 页面 Forbidden 用 page id。 */
export const PAGE_FLAGS = "page.flags";
export const PAGE_UI_AUTHZ = "page.ui-authz";
export const PAGE_DECLARATIONS = "page.declarations";
export const PAGE_TENANTS = "page.tenants";
export const PAGE_ORG = "page.org";
export const PAGE_INBOX = "page.inbox";
export const PAGE_INBOX_COMPOSE = "page.inbox.compose";
export const PAGE_INBOX_GROUPS = "page.inbox.groups";
export const PAGE_CONFIG = "page.config";
export const PAGE_AUDIT = "page.audit";
export const PAGE_DEAD_LETTERS = "page.dead-letters";
export const PAGE_SERVICES = "page.services";
export const PAGE_DEPLOY = "page.deploy";
export const PAGE_FORMS = "page.forms";
export const PAGE_PAGES = "page.pages";
export const PAGE_SEARCH = "page.search";
export const PAGE_CODEGEN = "page.codegen";
export const PAGE_CAPABILITIES = "page.capabilities";
export const PAGE_OPERATORS = "page.operators";

/**
 * Whether a button entry is allowed; Manifest wins when present, else {@code fallback}.
 * 按钮是否允许：Manifest 有该 id 时用 allowed，否则用 fallback（通常来自 me.permissions）。
 */
export function buttonAllowed(
  manifest: UiPermissionManifestDocument | undefined,
  buttonId: string,
  fallback: boolean,
): boolean {
  const entry = (manifest?.entries ?? []).find(
    (e) => e.kind === "button" && e.id === buttonId,
  );
  if (!entry) return fallback;
  return Boolean(entry.allowed);
}

/**
 * Whether a page entry is allowed; Manifest wins when present, else {@code fallback}.
 * 页面是否允许：Manifest 有该 id 时用 allowed，否则用 fallback。
 */
export function pageAllowed(
  manifest: UiPermissionManifestDocument | undefined,
  pageId: string,
  fallback: boolean,
): boolean {
  const entry = (manifest?.entries ?? []).find(
    (e) => e.kind === "page" && e.id === pageId,
  );
  if (!entry) return fallback;
  return Boolean(entry.allowed);
}
