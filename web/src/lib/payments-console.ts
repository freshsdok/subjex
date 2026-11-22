/**
 * Operator payment/refund console helpers — 操作员支付与退款控制台辅助。
 * Formats amounts and maps status/error codes for the read-only list and detail pages.
 * 为只读列表和详情页格式化金额，并映射状态与错误码。不提供创单、确认到账或退款动作。
 */

export type PaymentAmountView = {
  amountMinor?: number;
  currency?: string;
};

export type PaymentRefundView = {
  refundId?: string;
  amount?: PaymentAmountView;
  refundStatus?: string;
  providerRefundId?: string | null;
  createdAt?: string;
};

export type PaymentDocumentView = {
  paymentId: string;
  tenantId?: string;
  amount?: PaymentAmountView;
  refundedAmount?: PaymentAmountView;
  status?: string;
  reference?: string | null;
  payerSubjectId?: string | null;
  payerRealm?: string | null;
  providerId?: string | null;
  providerReference?: string | null;
  refunds?: PaymentRefundView[];
  createdAt?: string;
  updatedAt?: string;
};

export const PAYMENT_STATUS_VALUES = [
  "PENDING",
  "SUCCEEDED",
  "FAILED",
  "PARTIALLY_REFUNDED",
  "REFUNDED",
] as const;

export type PaymentStatusValue = (typeof PAYMENT_STATUS_VALUES)[number];

const STATUS_PHRASE_KEYS = {
  PENDING: "paymentsStatusPending",
  SUCCEEDED: "paymentsStatusSucceeded",
  FAILED: "paymentsStatusFailed",
  PARTIALLY_REFUNDED: "paymentsStatusPartiallyRefunded",
  REFUNDED: "paymentsStatusRefunded",
} as const;

/** Phrase key for a payment or refund status — 支付或退款状态对应的文案键. */
export function paymentStatusPhraseKey(
  status?: string,
): (typeof STATUS_PHRASE_KEYS)[PaymentStatusValue] | undefined {
  const name = (status || "").trim().toUpperCase();
  return STATUS_PHRASE_KEYS[name as PaymentStatusValue];
}

/** ISO 4217 fraction digits for a currency code; unknown codes default to 2. / 币种小数位，未知则按 2 位. */
export function currencyFractionDigits(currency: string): number {
  const code = currency.trim().toUpperCase();
  if (!code) return 2;
  try {
    const digits = new Intl.NumberFormat("en", { style: "currency", currency: code }).resolvedOptions()
      .maximumFractionDigits;
    return typeof digits === "number" ? digits : 2;
  } catch {
    return 2;
  }
}

/**
 * Format minor units as a currency amount (e.g. CNY 199 → ¥1.99).
 * 把最小货币单位格式化为金额（例如人民币 199 分显示为 ¥1.99）。
 */
export function formatPaymentAmount(
  amount: PaymentAmountView | undefined,
  locale: string,
): string {
  if (!amount || amount.amountMinor == null || !amount.currency) {
    return "";
  }
  const code = amount.currency.trim().toUpperCase();
  const digits = currencyFractionDigits(code);
  const value = amount.amountMinor / 10 ** digits;
  try {
    return new Intl.NumberFormat(locale, { style: "currency", currency: code }).format(value);
  } catch {
    return `${amount.amountMinor} ${code}`;
  }
}

/** Phrase key for a known payment query error reason — 支付查询错误原因对应的文案键. */
export function paymentQueryErrorPhraseKey(
  reason?: string,
): "paymentsNotFound" | "paymentsCrossTenantForbidden" | undefined {
  switch ((reason || "").trim()) {
    case "payment-not-found":
      return "paymentsNotFound";
    case "payment-cross-tenant-denied":
      return "paymentsCrossTenantForbidden";
    default:
      return undefined;
  }
}

/** Whether the list API should send a status filter — 列表查询是否应带上状态筛选. */
export function apiPaymentStatusFilter(raw?: string): string {
  const name = (raw || "").trim().toUpperCase();
  if (!name || name === "ALL") return "";
  return PAYMENT_STATUS_VALUES.includes(name as PaymentStatusValue) ? name : "";
}
