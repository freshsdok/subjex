import Link from "next/link";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  PAGE_INBOX_GROUPS,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { InboxGroupsConsole } from "./groups-console";

/**
 * Inbox groups page — IM-A7: CRUD mailing lists + members.
 * Enter: Manifest {@code page.inbox.groups} (inbox.group.admin).
 * 站内信组页：进入走 page.inbox.groups。
 */
export default async function InboxGroupsPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_INBOX_GROUPS, permissions.has("inbox.group.admin"))) {
    return <ForbiddenNotice phrases={phrases} permission="inbox.group.admin" />;
  }
  const jar = await cookies();
  const tenantHint =
    (jar.get(orgTenantCookieName)?.value ?? "").trim() ||
    parseDeclarationTenantCookie(jar.get(declarationTenantCookieName)?.value);
  return (
    <section>
      <div className="mb-2">
        <Link href="/inbox" className="text-sm text-muted hover:text-foreground">
          ← {phrases.inboxGroupsBackToInbox}
        </Link>
      </div>
      <PageHeading title={phrases.inboxGroupsTitle} hint={phrases.inboxGroupsHint} />
      <InboxGroupsConsole phrases={phrases} initialTenantId={tenantHint} />
    </section>
  );
}
