import type { PhraseBook } from "@/i18n/phrases";

/**
 * Console sections — label / emergency fallback catalog for the left rail (PC-3).
 * <p>
 * Gate source of truth is {@code GET /api/v1/ui-permissions} (classpath Manifest). This list
 * still supplies {@code labelKey} via href when merging, and is used if the Manifest API fails.
 * Do not add new gated menus here alone — update {@code ui-authz/console-ui.yaml} + label map.
 * <p>
 * 控制台栏目：文案与应急回退目录（PC-3）。门禁真源为 GET ui-permissions；本表仍提供 labelKey，
 * 并在 Manifest API 失败时回退。新增栏目请改 YAML + id→labelKey 映射，勿只改本文件。
 */
export interface ConsoleSection {
  /** In-app path (deep link) — 站内路径（深链）。 */
  href: string;
  labelKey: keyof PhraseBook;
  /** Permission to click; null = login only — 点击所需权限；null 表示登录即可。 */
  requiredPermission: string | null;
  /** Unlock if operator holds any of these (overrides single requiredPermission when set). */
  requiredAnyOf?: readonly string[];
}

export const consoleSections: ConsoleSection[] = [
  { href: "/", labelKey: "navOverview", requiredPermission: null },
  { href: "/services", labelKey: "navServices", requiredPermission: "registry.read" },
  { href: "/config", labelKey: "navConfig", requiredPermission: "config.read" },
  { href: "/flags", labelKey: "navFlags", requiredPermission: "config.read" },
  { href: "/inbox", labelKey: "navInbox", requiredPermission: "inbox.read" },
  {
    href: "/inbox/compose",
    labelKey: "navInboxCompose",
    requiredPermission: "inbox.send.system",
    requiredAnyOf: ["inbox.send.system", "inbox.broadcast"],
  },
  { href: "/inbox/groups", labelKey: "navInboxGroups", requiredPermission: "inbox.group.admin" },
  { href: "/audit", labelKey: "navAudit", requiredPermission: "admin.read" },
  { href: "/dead-letters", labelKey: "navDeadLetters", requiredPermission: "admin.read" },
  { href: "/deploy", labelKey: "navDeploy", requiredPermission: "page.read" },
  { href: "/forms", labelKey: "navForms", requiredPermission: "page.read" },
  { href: "/pages", labelKey: "navPages", requiredPermission: "page.read" },
  { href: "/search", labelKey: "navSearch", requiredPermission: "page.read" },
  { href: "/declarations", labelKey: "navDeclarations", requiredPermission: "declaration.read" },
  { href: "/codegen", labelKey: "navCodegen", requiredPermission: "page.read" },
  { href: "/operators", labelKey: "navOperators", requiredPermission: null },
  { href: "/tenants", labelKey: "navTenants", requiredPermission: "admin.read" },
  { href: "/org", labelKey: "navOrg", requiredPermission: "org.read" },
  { href: "/capabilities", labelKey: "navCapabilities", requiredPermission: "page.read" },
  { href: "/ui-authz", labelKey: "navUiAuthz", requiredPermission: "declaration.read" },
];
