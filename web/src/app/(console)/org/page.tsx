import { cookies } from "next/headers";
import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName, parseOrgTenantCookie } from "@/lib/org-console";
import {
  BUTTON_ORG_WRITE,
  buttonAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { OrgConsole } from "./org-console";

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string; tenantState?: string }>;
};

/**
 * Org page — Organization ontology console (O5 paths under /api/v1/organizations).
 * Gates: {@code org.read} enter; write controls use Manifest button {@code btn.org.write} (PC-4).
 * Deep link tenant cookie {@code subjex_org_tenant}. Read-only banner when write disallowed.
 * <p>
 * 组织页：org.read 可进；写入控件走 Manifest 按钮 btn.org.write（PC-4）。
 * 租户 cookie 深链；无写权限时只读横幅。
 */
export default async function OrgPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("org.read")) {
    return <ForbiddenNotice phrases={phrases} permission="org.read" />;
  }

  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  // Manifest button gate; fallback to me.permissions if entry missing — Manifest 按钮门禁；缺条目时回退。
  const canWrite = buttonAllowed(manifest, BUTTON_ORG_WRITE, permissions.has("org.write"));
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
