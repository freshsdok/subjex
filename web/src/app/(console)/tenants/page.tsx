import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { isReservedTenant } from "@/lib/tenant-admin";
import { CreateTenantForm } from "./create-tenant-form";
import { TenantAdminActions } from "./tenant-admin-actions";

type TenantsDocument = {
  tenants?: Array<{
    tenantId?: string;
    tenantName?: string;
    tenantState?: string;
  }>;
};

// Tenants page — 租户页：admin.read 可列表；tenant.manage 可创建、改名、禁用/启用。
export default async function TenantsPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  const canRead = permissions.has("admin.read");
  const canManage = permissions.has("tenant.manage");

  if (!canRead) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }

  const listed = await readPlatform<TenantsDocument>("tenants");
  if (listed.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (!listed.body) {
    return <LoadFailedNotice phrases={phrases} status={listed.status} />;
  }
  const tenants = listed.body.tenants ?? [];

  return (
    <section>
      <PageHeading title={phrases.tenantsTitle} hint={phrases.tenantsHint} />
      {!canManage ? <p className="mt-2 text-sm text-muted">{phrases.tenantManageRequired}</p> : null}
      {canManage ? <CreateTenantForm phrases={phrases} /> : null}
      <div className="mt-8">
        {tenants.length === 0 ? (
          <p className="text-sm text-muted">{phrases.emptyList}</p>
        ) : (
          <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
            <thead className="bg-background text-left text-muted">
              <tr>
                <th className="px-3 py-2 font-medium">{phrases.tenantIdColumn}</th>
                <th className="px-3 py-2 font-medium">{phrases.tenantNameColumn}</th>
                <th className="px-3 py-2 font-medium">{phrases.tenantStateColumn}</th>
                <th className="px-3 py-2 font-medium" />
              </tr>
            </thead>
            <tbody>
              {tenants.map((row) => {
                const id = row.tenantId ?? "";
                const reserved = isReservedTenant(id);
                return (
                  <tr key={id || row.tenantName} className="border-t border-border align-top">
                    <td className="px-3 py-2 font-mono text-xs">{row.tenantId}</td>
                    <td className="px-3 py-2">{row.tenantName}</td>
                    <td className="px-3 py-2">{row.tenantState}</td>
                    <td className="px-3 py-2">
                      {canManage && id && !reserved ? (
                        <TenantAdminActions
                          tenantId={id}
                          tenantName={row.tenantName ?? id}
                          tenantState={row.tenantState ?? "ACTIVE"}
                          phrases={phrases}
                        />
                      ) : reserved ? (
                        <span className="text-xs text-muted">{phrases.reservedTenantHint}</span>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
