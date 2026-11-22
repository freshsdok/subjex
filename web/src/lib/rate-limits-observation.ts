/**
 * Helpers for the operator rate-limit and delivery-breaker observation page.
 * 操作员限额与投递开合观察页的辅助方法。
 */

export function rateLimitBackendPhraseKey(backend: string | undefined): string {
  switch ((backend ?? "").trim().toLowerCase()) {
    case "process":
      return "rateLimitsBackendProcess";
    case "jdbc":
      return "rateLimitsBackendJdbc";
    default:
      return "rateLimitsBackendUnknown";
  }
}

export function circuitStatePhraseKey(state: string | undefined): string | undefined {
  switch ((state ?? "").trim().toLowerCase()) {
    case "closed":
      return "rateLimitsCircuitClosed";
    case "open":
      return "rateLimitsCircuitOpen";
    case "half-open":
      return "rateLimitsCircuitHalfOpen";
    default:
      return undefined;
  }
}
