import Link from "next/link";
import { AppearanceSwitcher } from "@/components/appearance-switcher";
import { SignOutButton } from "@/components/sign-out-button";
import { currentSkin } from "@/server/skin";
import { fillPhrase } from "@/i18n/phrases";
import { currentLanguage } from "@/i18n/server-language";
import { consoleSections } from "./console-sections";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

// Console shell — 控制台外框：顶栏显示当前操作员和退出，左侧导航；未登录会被跳到 /login。
export default async function ConsoleLayout({ children }: LayoutProps<"/">) {
  const { language, phrases } = await currentLanguage();
  const { skinNames, chosenSkinName, skinStyle } = await currentSkin();
  const { body: operator } = await readPlatform<OperatorSelfDocument>("me");
  const grantedPermissions = new Set(operator?.permissions ?? []);
  return (
    <div className="flex min-h-full flex-1 flex-col bg-background text-foreground" style={skinStyle}>
      <header className="flex items-center justify-between border-b border-border bg-surface px-6 py-3">
        <span className="font-semibold">{phrases.productName}</span>
        <div className="flex items-center gap-3 text-sm">
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
          {/* Locked sections stay visible but greyed, with the missing permission named — 没权限的栏目不隐藏，置灰并写明缺什么权限。 */}
          {consoleSections.map((section) => {
            const allowed =
              section.requiredPermission === null || grantedPermissions.has(section.requiredPermission);
            return allowed ? (
              <Link key={section.href} href={section.href} className="block rounded-md px-2 py-1 hover:bg-background">
                {phrases[section.labelKey]}
              </Link>
            ) : (
              <span
                key={section.href}
                aria-disabled="true"
                title={fillPhrase(phrases.lockedSectionHint, { permission: section.requiredPermission ?? "" })}
                className="block cursor-not-allowed px-2 py-1 text-muted opacity-60"
              >
                {phrases[section.labelKey]}
              </span>
            );
          })}
        </nav>
        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  );
}
