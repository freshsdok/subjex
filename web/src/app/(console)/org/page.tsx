import { cookies } from "next/headers";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName, parseOrgTenantCookie } from "@/lib/org-console";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { OrgConsole } from "./org-console";

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string; tenantState?: string }>;
};

/**
 * Org page — Organization ontology console (O5 paths under /api/v1/organizations).
 * Gates: {@code org.read} enter; {@code org.write} enable forms. Deep link tenant cookie
 * {@code subjex_org_tenant}. Read-only banner explains why writes are disabled.
 * <p>
 * 组织页：org.read 可进；org.write 才可改。租户 cookie 深链；只读横幅说明为何禁用写入。
 */
export default async function OrgPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("org.read")) {
    return <ForbiddenNotice phrases={phrases} permission="org.read" />;
  }

  const canWrite = permissions.has("org.write");
  const canListTenants = permissions.has("admin.read");
  let tenantOptions: Array<{ tenantId: string; tenantName: string }> = [];
  if (canListTenants) {
    const listed = await readPlatform<TenantsDocument>("tenants");
    tenantOptions = (listed.body?.tenants ?? [])
      .filter((row): row is { tenantId: string; tenantName?: string } => Boolean(row.tenantId))
      .map((row) => ({ tenantId: row.tenantId, tenantName: row.tenantName ?? row.tenantId }));
  }

  const cookieJar = await cookies();
  const initialTenantId = parseOrgTenantCookie(cookieJar.get(orgTenantCookieName)?.value);

  return (
    <section>
      <PageHeading title={phrases.orgTitle} hint={phrases.orgHint} />
      {!canWrite ? (
        <p
          role="status"
          className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted"
        >
          {phrases.orgWriteRequired}
        </p>
      ) : null}
      <OrgConsole
        phrases={phrases}
        canWrite={canWrite}
        tenantOptions={tenantOptions}
        initialTenantId={initialTenantId}
      />
    </section>
  );
}
