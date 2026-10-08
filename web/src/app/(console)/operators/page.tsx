import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { ChangePasswordForm } from "./change-password-form";
import { MfaEnrollForm } from "./mfa-enroll-form";
import { CreateOperatorForm } from "./create-operator-form";
import { OperatorAdminActions } from "./operator-admin-actions";
import { TenantGrantsEditor } from "./tenant-grants-editor";
import { IdpLinkEditor } from "./idp-link-editor";

type OperatorsDocument = {
  operators?: Array<{
    loginName?: string;
    accountState?: string;
    subjectId?: string;
    identityId?: string;
    roleName?: string;
  }>;
};

type TenantGrantsDocument = { loginName?: string; tenantIds?: string[] };

type IdpLinkDocument = {
  loginName?: string;
  issuer?: string | null;
  idpSubject?: string | null;
  linked?: boolean;
};

// Operators page — 操作员页：人人可改自己的口令；operator.manage 可新建、重置口令、禁用/启用并编辑租户授权。
export default async function OperatorsPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument & { mfaEnrolled?: boolean }>("me");
  const canManage = new Set(me?.permissions ?? []).has("operator.manage");
  const mfaEnrolled = Boolean(me?.mfaEnrolled);

  let operators: NonNullable<OperatorsDocument["operators"]> = [];
  let listStatus = 200;
  const tenantIdsByLogin: Record<string, string[]> = {};
  const idpLinkByLogin: Record<string, IdpLinkDocument> = {};
  if (canManage) {
    const listed = await readPlatform<OperatorsDocument>("operators");
    listStatus = listed.status;
    if (listStatus === 403) return <ForbiddenNotice phrases={phrases} permission="operator.manage" />;
    if (!listed.body) return <LoadFailedNotice phrases={phrases} status={listStatus} />;
    operators = listed.body.operators ?? [];
    for (const row of operators) {
      if (!row.loginName) continue;
      const grants = await readPlatform<TenantGrantsDocument>(
        `operators/${encodeURIComponent(row.loginName)}/tenants`,
      );
      tenantIdsByLogin[row.loginName] = grants.body?.tenantIds ?? [];
      const idp = await readPlatform<IdpLinkDocument>(
        `operators/${encodeURIComponent(row.loginName)}/idp-link`,
      );
      idpLinkByLogin[row.loginName] = idp.body ?? { loginName: row.loginName, linked: false };
    }
  }

  function grantsLabel(loginName: string | undefined): string {
    if (!loginName) return phrases.noTenantGrantsLabel;
    const ids = tenantIdsByLogin[loginName] ?? [];
    return ids.length === 0 ? phrases.noTenantGrantsLabel : ids.join(", ");
  }

  return (
    <section>
      <PageHeading title={phrases.operatorsTitle} hint={phrases.operatorsHint} />
      <ChangePasswordForm phrases={phrases} />
      <MfaEnrollForm phrases={phrases} enrolled={mfaEnrolled} />
      {canManage ? (
        <>
          <CreateOperatorForm phrases={phrases} />
          <div className="mt-8">
            {operators.length === 0 ? (
              <p className="text-sm text-muted">{phrases.emptyList}</p>
            ) : (
              <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
                <thead className="bg-background text-left text-muted">
                  <tr>
                    <th className="px-3 py-2 font-medium">{phrases.operatorLoginColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.operatorStateColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.operatorRoleColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.operatorTenantsColumn}</th>
                    <th className="px-3 py-2 font-medium" />
                  </tr>
                </thead>
                <tbody>
                  {operators.map((row) => {
                    const isSelf = Boolean(row.loginName && row.loginName === me?.loginName);
                    return (
                      <tr key={row.loginName} className="border-t border-border align-top">
                        <td className="px-3 py-2 font-mono text-xs">{row.loginName}</td>
                        <td className="px-3 py-2">{row.accountState}</td>
                        <td className="px-3 py-2">{row.roleName}</td>
                        <td className="px-3 py-2 font-mono text-xs">{grantsLabel(row.loginName)}</td>
                        <td className="px-3 py-2">
                          {row.loginName && !isSelf ? (
                            <OperatorAdminActions
                              loginName={row.loginName}
                              accountState={row.accountState ?? "ACTIVE"}
                              tenantIds={tenantIdsByLogin[row.loginName] ?? []}
                              phrases={phrases}
                            />
                          ) : row.loginName ? (
                            <TenantGrantsEditor
                              loginName={row.loginName}
                              initialTenantIds={tenantIdsByLogin[row.loginName] ?? []}
                              phrases={phrases}
                            />
                          ) : null}
                          {row.loginName ? (
                            <IdpLinkEditor
                              loginName={row.loginName}
                              initial={idpLinkByLogin[row.loginName] ?? { loginName: row.loginName, linked: false }}
                              phrases={phrases}
                            />
                          ) : null}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        </>
      ) : null}
    </section>
  );
}
