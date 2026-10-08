import { cookies } from "next/headers";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  declarationTenantCookieName,
  parseDeclarationTenantCookie,
} from "@/lib/declaration-draft";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { DeclarationsConsole } from "./declarations-console";

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string; tenantState?: string }>;
};

// Declarations page — 声明草稿页：declaration.read 可进；write 才可保存。
export default async function DeclarationsPage() {
  const { language, phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("declaration.read")) {
    return <ForbiddenNotice phrases={phrases} permission="declaration.read" />;
  }

  const canWrite = permissions.has("declaration.write");
  const canPromote = permissions.has("declaration.promote");
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
