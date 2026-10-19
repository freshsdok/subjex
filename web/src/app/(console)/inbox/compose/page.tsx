import Link from "next/link";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { declarationTenantCookieName, parseDeclarationTenantCookie } from "@/lib/declaration-draft";
import { canOpenCompose } from "@/lib/inbox-compose";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_INBOX_COMPOSE_SEND,
  buttonAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { InboxComposeConsole } from "./compose-console";

/**
 * Inbox compose page — IM-A7: system / group-send / org broadcast (+ attachments).
 * Send button gated by Manifest {@code btn.inbox.compose.send} (PC-4); APIs still enforce.
 * 写信页：发送按钮走 Manifest btn.inbox.compose.send；后端仍强制校验。
 */
export default async function InboxComposePage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  if (!canOpenCompose(permissions)) {
    return <ForbiddenNotice phrases={phrases} permission="inbox.send.system|inbox.broadcast" />;
  }
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  const canSend = buttonAllowed(
    manifest,
    BUTTON_INBOX_COMPOSE_SEND,
    canOpenCompose(permissions),
  );
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
        canSend={canSend}
      />
    </section>
  );
}
