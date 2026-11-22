/**
 * Helpers for the operator end-user directory (existing identity rows only).
 * 操作员终端用户名册辅助方法（只展示已有身份行）。
 */

export type EndUserDirectoryRow = {
  tenantId: string;
  subjectId: string;
  accountId: string;
  displayName: string;
  accountState: string;
  membershipState: string;
  phoneMasked?: string | null;
  idp?: { providerId?: string | null; issuer?: string | null } | null;
};

/** Map a stored account/membership state to a phrase key, or undefined if unknown. */
export function endUserStatePhraseKey(state: string | undefined): string | undefined {
  switch ((state ?? "").trim().toUpperCase()) {
    case "ACTIVE":
      return "endUsersStateActive";
    case "SUSPENDED":
      return "endUsersStateSuspended";
    case "RETIRED":
      return "endUsersStateRetired";
    case "REVOKED":
      return "endUsersStateRevoked";
    default:
      return undefined;
  }
}

/** Build the console path for a tenant-scoped directory lookup. */
export function endUsersConsoleQuery(tenantId: string, query?: string): string {
  const tid = tenantId.trim();
  const q = (query ?? "").trim();
  const params = new URLSearchParams();
  if (tid) {
    params.set("tenantId", tid);
  }
  if (q) {
    params.set("q", q);
  }
  const encoded = params.toString();
  return encoded ? `/end-users?${encoded}` : "/end-users";
}
