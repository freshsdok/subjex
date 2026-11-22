import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { PAGE_END_USERS, pageAllowed, type UiPermissionManifestDocument } from "@/lib/ui-permissions";
import { type EndUserDirectoryRow } from "@/lib/end-user-directory";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { EndUserDirectoryConsole } from "./end-user-directory-console";

type DirectoryDocument = {
  tenantId?: string;
  users?: Array<{
    tenantId?: string;
    subjectId?: string;
    accountId?: string;
    displayName?: string;
    accountState?: string;
    membershipState?: string;
    phoneMasked?: string | null;
    idp?: { providerId?: string | null; issuer?: string | null } | null;
  }>;
};

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string }>;
};

/**
 * Operator end-user directory — existing identity rows only; no last-login, disable, or unlink.
 * Enter: Manifest {@code page.end-users} (end-user.read).
 * 操作员终端用户名册：只展示已有身份行；无最后登录、停用或解绑。进入走 page.end-users。
 */
export default async function EndUsersPage({
  searchParams,
}: {
  searchParams: Promise<{ tenantId?: string | string[]; q?: string | string[] }>;
}) {
  const { phrases } = await currentLanguage();
  const params = await searchParams;
  const tenantParam = Array.isArray(params.tenantId) ? params.tenantId[0] : params.tenantId;
  const queryParam = Array.isArray(params.q) ? params.q[0] : params.q;
  const tenantId = (tenantParam ?? "").trim();
  const query = (queryParam ?? "").trim();
  let directoryPath = "";
  if (tenantId) {
    const search = new URLSearchParams();
    search.set("tenantId", tenantId);
    if (query) {
      search.set("q", query);
    }
    directoryPath = `end-users?${search.toString()}`;
  }
  const [{ body: me }, uiPermissionsRead, tenantsRead, directoryRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
    readPlatform<TenantsDocument>("tenants"),
    tenantId
      ? readPlatform<DirectoryDocument>(directoryPath)
      : Promise.resolve({ status: 200, body: { users: [] } as DirectoryDocument }),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_END_USERS, permissions.has("end-user.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="end-user.read" />;
  }
  const directoryProblem =
    tenantId && directoryRead.status === 403
      ? "endUsersForbidden"
      : tenantId && !directoryRead.body
        ? "endUsersLoadFailed"
        : null;
  if (!directoryProblem && tenantId && directoryRead.status >= 500) {
    return <LoadFailedNotice phrases={phrases} status={directoryRead.status} />;
  }
  return (
    <section>
      <PageHeading title={phrases.endUsersTitle} hint={phrases.endUsersHint} />
      <EndUserDirectoryConsole
        phrases={phrases}
        selectedTenantId={tenantId}
        selectedQuery={query}
        problem={directoryProblem}
        users={toRows(directoryRead.body?.users)}
        tenants={(tenantsRead.body?.tenants ?? [])
          .map((row) => ({ tenantId: row.tenantId ?? "", tenantName: row.tenantName ?? "" }))
          .filter((row) => row.tenantId)}
      />
    </section>
  );
}

function toRows(rows: DirectoryDocument["users"]): EndUserDirectoryRow[] {
  return (rows ?? [])
    .filter((row) => (row.subjectId ?? "").trim())
    .map((row) => ({
      tenantId: (row.tenantId ?? "").trim(),
      subjectId: (row.subjectId ?? "").trim(),
      accountId: (row.accountId ?? "").trim(),
      displayName: (row.displayName ?? "").trim(),
      accountState: (row.accountState ?? "").trim(),
      membershipState: (row.membershipState ?? "").trim(),
      phoneMasked: row.phoneMasked ?? null,
      idp: row.idp ?? null,
    }));
}
