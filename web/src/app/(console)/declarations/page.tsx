import { cookies } from "next/headers";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  declarationTenantCookieName,
  parseDeclarationTenantCookie,
} from "@/lib/declaration-draft";
import {
  BUTTON_DECLARATIONS_PROMOTE,
  BUTTON_DECLARATIONS_WRITE,
  PAGE_DECLARATIONS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { DeclarationsConsole } from "./declarations-console";

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string; tenantState?: string }>;
};

/**
 * Declarations page — YAML drafts console.
 * Enter: Manifest {@code page.declarations}; write/promote buttons via #5 ids.
 * 声明页：进入 page.declarations；写/晋升走 Manifest 按钮（#5）。
 */
export default async function DeclarationsPage() {
  const { language, phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_DECLARATIONS, permissions.has("declaration.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="declaration.read" />;
  }

  const canWrite = buttonAllowed(
    manifest,
    BUTTON_DECLARATIONS_WRITE,
    permissions.has("declaration.write"),
  );
  const canPromote = buttonAllowed(
    manifest,
    BUTTON_DECLARATIONS_PROMOTE,
    permissions.has("declaration.promote"),
  );
  const canMigrate = permissions.has("declaration.migrate");
  const canListTenants = permissions.has("admin.read");
  let tenantOptions: Array<{ tenantId: string; tenantName: string }> = [];
  if (canListTenants) {
    const listed = await readPlatform<TenantsDocument>("tenants");
    tenantOptions = (listed.body?.tenants ?? [])
      .filter((row): row is { tenantId: string; tenantName?: string } => Boolean(row.tenantId))
      .map((row) => ({ tenantId: row.tenantId, tenantName: row.tenantName ?? row.tenantId }));
  }

  const cookieJar = await cookies();
  const initialTenantId = parseDeclarationTenantCookie(cookieJar.get(declarationTenantCookieName)?.value);

  return (
    <section>
      <PageHeading title={phrases.declarationsTitle} hint={phrases.declarationsHint} />
      {!canWrite ? (
        <p role="status" className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.declarationsWriteForbidden}
        </p>
      ) : null}
      {!canPromote ? (
        <p role="status" className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.declarationsPromoteForbidden}
        </p>
      ) : null}
      {!canMigrate ? (
        <p role="status" className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.declarationsMigrateForbidden}
        </p>
      ) : null}
      <DeclarationsConsole
        phrases={phrases}
        language={language}
        canWrite={canWrite}
        canPromote={canPromote}
        canMigrate={canMigrate}
        tenantOptions={tenantOptions}
        initialTenantId={initialTenantId}
      />
    </section>
  );
}
