import Link from "next/link";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { canOpenCompose } from "@/lib/inbox-compose";
import { orgTenantCookieName } from "@/lib/org-console";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { InboxComposeConsole } from "./compose-console";

/**
 * Inbox compose page — IM-A7: system / group-send / org broadcast (+ attachments).
 * 写信页：系统 / 组群发 / 组织广播（可附文件）。
 */
export default async function InboxComposePage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!canOpenCompose(permissions)) {
    return <ForbiddenNotice phrases={phrases} permission="inbox.send.system|inbox.broadcast" />;
  }
  const jar = await cookies();
  const tenantHint =
    (jar.get(orgTenantCookieName)?.value ?? "").trim() ||
    parseDeclarationTenantCookie(jar.get(declarationTenantCookieName)?.value);
  return (
    <section>
      <div className="mb-2">
        <Link href="/inbox" className="text-sm text-muted hover:text-foreground">
          ← {phrases.inboxComposeBackToInbox}
        </Link>
      </div>
      <PageHeading title={phrases.inboxComposeTitle} hint={phrases.inboxComposeHint} />
      <InboxComposeConsole
        phrases={phrases}
        initialTenantId={tenantHint}
        permissionNames={[...permissions]}
      />
    </section>
  );
}
