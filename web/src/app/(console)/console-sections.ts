import type { PhraseBook } from "@/i18n/phrases";

/**
 * Console sections — nav catalog for the left rail.
 * <p>
 * {@code href} is the in-app deep link (Next App Router path). {@code requiredPermission}
 * gates visibility as clickable vs greyed: null = any signed-in operator; otherwise the
 * operator must hold that named permission (layout shows locked hint = why disabled).
 * Order is the user-facing tour: overview → ops surfaces → declarations → org → capabilities.
 * <p>
 * 控制台栏目：左侧导航目录。href 为站内深链；requiredPermission 为查看门禁（null=登录即可）。
 * 无权限时不隐藏，置灰并用 title 写明缺什么权限（为何禁用）。
 */
export interface ConsoleSection {
  /** In-app path (deep link) — 站内路径（深链）。 */
  href: string;
  labelKey: keyof PhraseBook;
  /** Permission to click; null = login only — 点击所需权限；null 表示登录即可。 */
  requiredPermission: string | null;
}

export const consoleSections: ConsoleSection[] = [
  { href: "/", labelKey: "navOverview", requiredPermission: null },
  { href: "/services", labelKey: "navServices", requiredPermission: "registry.read" },
  { href: "/config", labelKey: "navConfig", requiredPermission: "config.read" },
  { href: "/audit", labelKey: "navAudit", requiredPermission: "admin.read" },
  { href: "/deploy", labelKey: "navDeploy", requiredPermission: "page.read" },
  { href: "/forms", labelKey: "navForms", requiredPermission: "page.read" },
  { href: "/pages", labelKey: "navPages", requiredPermission: "page.read" },
  { href: "/declarations", labelKey: "navDeclarations", requiredPermission: "declaration.read" },
  { href: "/codegen", labelKey: "navCodegen", requiredPermission: "page.read" },
  { href: "/operators", labelKey: "navOperators", requiredPermission: null },
  { href: "/tenants", labelKey: "navTenants", requiredPermission: "admin.read" },
  { href: "/org", labelKey: "navOrg", requiredPermission: "org.read" },
  { href: "/capabilities", labelKey: "navCapabilities", requiredPermission: "page.read" },
];
