import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { orgTenantCookieName } from "@/lib/org-console";
import { cookies } from "next/headers";
import { InboxConsole } from "./inbox-console";

/**
 * Inbox page — IM-4 operator mailbox (inbox.read).
 * 站内信页：操作员收件箱（inbox.read）。
 */
export default async function InboxPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("inbox.read")) {
    return <ForbiddenNotice phrases={phrases} permission="inbox.read" />;
  }
  const jar = await cookies();
  const tenantHint =
    (jar.get(orgTenantCookieName)?.value ?? "").trim() ||
    parseDeclarationTenantCookie(jar.get(declarationTenantCookieName)?.value);
  return (
    <section>
      <PageHeading title={phrases.inboxTitle} hint={phrases.inboxHint} />
      <InboxConsole phrases={phrases} initialTenantId={tenantHint} />
    </section>
  );
}
