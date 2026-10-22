import Link from "next/link";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { canOpenCompose } from "@/lib/inbox-compose";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  PAGE_INBOX,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { cookies } from "next/headers";
import { InboxConsole } from "./inbox-console";

/**
 * Inbox page — IM-4 operator mailbox; IM-A7 links to compose/groups.
 * Enter: Manifest {@code page.inbox} (inbox.read).
 * 站内信页：进入走 page.inbox；写信/组管理入口。
 */
export default async function InboxPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_INBOX, permissions.has("inbox.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="inbox.read" />;
  }
  const jar = await cookies();
  const tenantHint =
    (jar.get(orgTenantCookieName)?.value ?? "").trim() ||
    parseDeclarationTenantCookie(jar.get(declarationTenantCookieName)?.value);
  const showCompose = canOpenCompose(permissions);
  const showGroups = permissions.has("inbox.group.admin");
  return (
    <section>
      <PageHeading title={phrases.inboxTitle} hint={phrases.inboxHint} />
      {showCompose || showGroups ? (
        <p className="mt-2 flex flex-wrap gap-3 text-sm">
          {showCompose ? (
            <Link href="/inbox/compose" className="text-foreground underline hover:opacity-80">
              {phrases.inboxComposeLink}
            </Link>
          ) : null}
          {showGroups ? (
            <Link href="/inbox/groups" className="text-foreground underline hover:opacity-80">
              {phrases.inboxGroupsLink}
            </Link>
          ) : null}
        </p>
      ) : null}
      <InboxConsole phrases={phrases} initialTenantId={tenantHint} />
    </section>
  );
}
