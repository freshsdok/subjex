import Link from "next/link";
import type { ReactNode } from "react";
import { AppearanceSwitcher } from "@/components/appearance-switcher";
import { InboxUnreadBadge } from "@/components/inbox-unread-badge";
import { SignOutButton } from "@/components/sign-out-button";
import { currentSkin } from "@/server/skin";
import { fillPhrase } from "@/i18n/phrases";
import { currentLanguage } from "@/i18n/server-language";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  buildConsoleNavItems,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { consoleSections } from "./console-sections";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";

/**
 * Console shell — top bar + left nav gated by UI permission Manifest (ADR 0010 / PC-3).
 * Unauthenticated → /login. Locked nav stays visible but greyed with missing permission in title.
 * <p>
 * 控制台外框：顶栏 + 左侧栏门禁来自 UI 权限 Manifest。未登录跳 /login。
 * 无权限栏目不隐藏，置灰并用 title 写明缺权限。
 */
// ReactNode keeps typecheck independent of Next-generated LayoutProps / `.next/types`.
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const { language, phrases } = await currentLanguage();
  const { skinNames, chosenSkinName, skinStyle } = await currentSkin();
  const [{ body: operator }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const grantedPermissions = new Set(operator?.permissions ?? []);
  const navItems = buildConsoleNavItems(
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined,
    consoleSections,
    grantedPermissions,
  );
  const jar = await cookies();
  const inboxTenantHint =
    (jar.get(orgTenantCookieName)?.value ?? "").trim() ||
    parseDeclarationTenantCookie(jar.get(declarationTenantCookieName)?.value);
  return (
    <div className="flex min-h-full flex-1 flex-col bg-background text-foreground" style={skinStyle}>
      <header className="flex items-center justify-between border-b border-border bg-surface px-6 py-3">
        <span className="font-semibold">{phrases.productName}</span>
        <div className="flex items-center gap-3 text-sm">
          {grantedPermissions.has("inbox.read") ? (
            <InboxUnreadBadge phrases={phrases} initialTenantId={inboxTenantHint} />
          ) : null}
          <AppearanceSwitcher
            phrases={phrases}
            language={language}
            skinNames={skinNames}
            chosenSkinName={chosenSkinName}
          />
          <span className="text-muted">
            {phrases.signedInAs}: <strong className="text-foreground">{operator?.loginName}</strong>
          </span>
          <SignOutButton label={phrases.signOutAction} />
        </div>
      </header>
      <div className="flex flex-1">
        <nav className="w-48 border-r border-border bg-surface p-4 text-sm">
          {/* Locked sections stay visible but greyed — 没权限的栏目不隐藏，置灰并写明缺什么权限。 */}
          {navItems.map((item) =>
            item.allowed ? (
              <Link
                key={item.id}
                href={item.href}
                className="block rounded-md px-2 py-1 hover:bg-background"
              >
                {phrases[item.labelKey]}
              </Link>
            ) : (
              <span
                key={item.id}
                aria-disabled="true"
                title={fillPhrase(phrases.lockedSectionHint, {
                  permission: item.missingPermissionHint,
                })}
                className="block cursor-not-allowed px-2 py-1 text-muted opacity-60"
              >
                {phrases[item.labelKey]}
              </span>
            ),
          )}
        </nav>
        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  );
}
