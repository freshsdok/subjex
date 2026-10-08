import type { PhraseBook } from "@/i18n/phrases";

// Console sections — 控制台栏目：路径、导航文案键、查看所需权限（null 表示登录即可）。
export interface ConsoleSection {
  href: string;
  labelKey: keyof PhraseBook;
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
];
