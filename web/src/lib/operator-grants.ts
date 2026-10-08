// Operator grant helpers — 操作员租户授权辅助：规范化、增删、比较；无授权即失败关闭。

export const OPERATOR_ROLE_OPTIONS = ["platform-operator", "platform-reader"] as const;
export type OperatorRoleName = (typeof OPERATOR_ROLE_OPTIONS)[number];

export const MIN_OPERATOR_PASSWORD_LENGTH = 8;

/** Trim and accept `*` or a single token without whitespace. */
export function normalizeTenantId(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/\s/.test(trimmed)) return null;
  return trimmed;
}

export function addTenantGrant(
  current: string[],
  raw: string,
): { next: string[]; error?: "blank" | "duplicate" | "invalid" } {
  const id = normalizeTenantId(raw);
  if (id === null) {
    return { next: current, error: raw.trim() === "" ? "blank" : "invalid" };
  }
  if (current.includes(id)) {
    return { next: current, error: "duplicate" };
  }
  return { next: [...current, id] };
}

export function removeTenantGrant(current: string[], tenantId: string): string[] {
  return current.filter((id) => id !== tenantId);
}

export function grantsEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const left = [...a].sort();
  const right = [...b].sort();
  return left.every((value, index) => value === right[index]);
}

export function isOperatorRoleName(value: string): value is OperatorRoleName {
  return (OPERATOR_ROLE_OPTIONS as readonly string[]).includes(value);
}
