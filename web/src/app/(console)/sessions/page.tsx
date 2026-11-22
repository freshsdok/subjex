import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  BUTTON_SESSIONS_REVOKE,
  PAGE_SESSIONS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { SessionListConsole, type SessionRow } from "./session-list-console";

type SessionsDocument = {
  sessions?: Array<{
    familyId?: string;
    realm?: string;
    subjectId?: string;
    displayName?: string;
    tenantId?: string;
    userAgent?: string;
    clientIp?: string;
    expiresAt?: string;
  }>;
};

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string }>;
};

/**
 * Current logins page — refresh-token families only (no invented device/last-used fields).
 * Enter: Manifest {@code page.sessions} (admin.read). Revoke: {@code btn.sessions.revoke} (operator.manage).
 * 当前登录页：只展示刷新令牌族（不编造设备名或最后使用时间）。进入走 page.sessions；吊销走 btn.sessions.revoke。
 */
export default async function SessionsPage({
  searchParams,
}: {
  searchParams: Promise<{ tenantId?: string | string[] }>;
}) {
  const { language, phrases } = await currentLanguage();
  const params = await searchParams;
  const tenantParam = Array.isArray(params.tenantId) ? params.tenantId[0] : params.tenantId;
  const tenantId = (tenantParam ?? "").trim();
  const endUserPath = tenantId
    ? `sessions/end-users?tenantId=${encodeURIComponent(tenantId)}`
    : "";
  const [{ body: me }, uiPermissionsRead, operatorRead, tenantsRead, endUserRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
    readPlatform<SessionsDocument>("sessions/operators"),
    readPlatform<TenantsDocument>("tenants"),
    tenantId
      ? readPlatform<SessionsDocument>(endUserPath)
      : Promise.resolve({ status: 200, body: { sessions: [] } as SessionsDocument }),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_SESSIONS, permissions.has("admin.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (operatorRead.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (!operatorRead.body) {
    return <LoadFailedNotice phrases={phrases} status={operatorRead.status} />;
  }
  const canRevoke = buttonAllowed(manifest, BUTTON_SESSIONS_REVOKE, permissions.has("operator.manage"));
  const endUserProblem =
    tenantId && endUserRead.status === 403
      ? "sessionsEndUserForbidden"
      : tenantId && !endUserRead.body
        ? "sessionsEndUserLoadFailed"
        : null;
  return (
    <section>
      <PageHeading title={phrases.sessionsTitle} hint={phrases.sessionsHint} />
      <SessionListConsole
        phrases={phrases}
        language={language}
        canRevoke={canRevoke}
        selectedTenantId={tenantId}
        endUserProblem={endUserProblem}
        operatorSessions={toRows(operatorRead.body.sessions)}
        endUserSessions={toRows(endUserRead.body?.sessions)}
        tenants={(tenantsRead.body?.tenants ?? [])
          .map((row) => ({ tenantId: row.tenantId ?? "", tenantName: row.tenantName ?? "" }))
          .filter((row) => row.tenantId)}
      />
    </section>
  );
}

function toRows(rows: SessionsDocument["sessions"]): SessionRow[] {
  return (rows ?? [])
    .filter((row) => (row.familyId ?? "").trim())
    .map((row) => ({
      familyId: (row.familyId ?? "").trim(),
      realm: (row.realm ?? "").trim(),
      subjectId: (row.subjectId ?? "").trim(),
      displayName: (row.displayName ?? "").trim(),
      tenantId: (row.tenantId ?? "").trim() || undefined,
      userAgent: row.userAgent,
      clientIp: row.clientIp,
      expiresAt: row.expiresAt,
    }));
}
