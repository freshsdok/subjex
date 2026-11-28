/**
 * End-user own payments: create, start charge, list, status, cancel PENDING.
 * 终端本人支付单：创单、发起扣款、列出、查看状态、取消待支付。不是货品目录。
 */

import { authedFetch, PlatformHttpError } from "./platform";

export type PaymentStatus =
  | "PENDING"
  | "SUCCEEDED"
  | "FAILED"
  | "PARTIALLY_REFUNDED"
  | "REFUNDED"
  | "EXPIRED"
  | "CANCELLED"
  | string;

export type PaymentAmount = {
  amountMinor?: number;
  currency?: string;
};

export type EndUserPayment = {
  paymentId: string;
  tenantId?: string;
  amount?: PaymentAmount;
  refundedAmount?: PaymentAmount;
  status?: PaymentStatus;
  reference?: string | null;
  referenceType?: string | null;
  referenceId?: string | null;
  memo?: string | null;
  createdAt?: string;
  updatedAt?: string;
  expiresAt?: string;
  succeededAt?: string;
  cancelledAt?: string;
};

export type PaymentChargePayload = {
  providerId?: string;
  params?: Record<string, unknown>;
};

export type PaymentChargeSession = {
  payment?: EndUserPayment;
  charge?: PaymentChargePayload;
};

export type CreatePaymentInput = {
  amountMinor: number;
  currency: string;
  referenceType: string;
  referenceId: string;
  memo?: string;
  idempotencyKey: string;
};

const PAYMENTS_PATH = "/api/v1/end-user/payments";

/** List own payments in the current tenant, newest first — 列出本组织本人单据，从新到旧. */
export async function listOwnPayments(status?: string): Promise<EndUserPayment[]> {
  const trimmed = (status || "").trim();
  const path = trimmed
    ? `${PAYMENTS_PATH}?status=${encodeURIComponent(trimmed)}`
    : PAYMENTS_PATH;
  const body = await authedFetch<EndUserPayment[]>({
    path,
    tenantId: true,
  });
  return Array.isArray(body) ? body : [];
}

export async function getOwnPayment(paymentId: string): Promise<EndUserPayment> {
  const id = paymentId.trim();
  return authedFetch<EndUserPayment>({
    path: `${PAYMENTS_PATH}/${encodeURIComponent(id)}`,
    tenantId: true,
  });
}

/** Cancel a PENDING payment the caller owns — 取消本人待支付单据. */
export async function cancelOwnPayment(paymentId: string): Promise<EndUserPayment> {
  const id = paymentId.trim();
  return authedFetch<EndUserPayment>({
    method: "POST",
    path: `${PAYMENTS_PATH}/${encodeURIComponent(id)}/cancel`,
    tenantId: true,
  });
}

/** Create a generic payment for the signed-in subject — 为当前登录主体创建一笔通用支付单. */
export async function createOwnPayment(input: CreatePaymentInput): Promise<EndUserPayment> {
  return authedFetch<EndUserPayment>({
    method: "POST",
    path: PAYMENTS_PATH,
    tenantId: true,
    headers: { "X-Idempotency-Token": input.idempotencyKey },
    body: {
      amountMinor: input.amountMinor,
      currency: input.currency,
      reference: { type: input.referenceType, id: input.referenceId },
      memo: input.memo || undefined,
    },
  });
}

/** Start charge on a PENDING payment the caller owns — 对本人待支付单据发起扣款. */
export async function startChargeOwnPayment(paymentId: string): Promise<PaymentChargeSession> {
  const id = paymentId.trim();
  return authedFetch<PaymentChargeSession>({
    method: "POST",
    path: `${PAYMENTS_PATH}/${encodeURIComponent(id)}/charge`,
    tenantId: true,
  });
}

export function canCancelPayment(row: EndUserPayment): boolean {
  return (row.status || "").toUpperCase() === "PENDING";
}

export function canStartCharge(row: EndUserPayment): boolean {
  return (row.status || "").toUpperCase() === "PENDING";
}

/** Convert a major-unit decimal string to minor units (default 2 fraction digits). */
/** 将主要货币单位小数转为最小单位，默认两位小数. */
export function majorToMinor(major: string, currency: string): number | null {
  const raw = (major || "").trim();
  if (!raw) return null;
  const digits = currency === "JPY" || currency === "KRW" ? 0 : 2;
  const normalized = raw.replace(",", ".");
  if (!/^\d+(\.\d+)?$/.test(normalized)) return null;
  const [whole, frac = ""] = normalized.split(".");
  if (digits === 0) {
    if (frac && Number(frac) !== 0) return null;
    return Number(whole);
  }
  if (frac.length > digits) return null;
  const padded = (frac + "0".repeat(digits)).slice(0, digits);
  return Number(whole) * 10 ** digits + Number(padded || "0");
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `pay-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** Human amount for known ISO currencies (default 2 fraction digits) — 按币种小数位展示金额，默认两位. */
export function paymentAmountLabel(amount?: PaymentAmount): string {
  const currency = (amount?.currency || "").trim() || "金额";
  const minor = Number(amount?.amountMinor ?? 0);
  const digits = currency === "JPY" || currency === "KRW" ? 0 : 2;
  const factor = 10 ** digits;
  const major = digits === 0 ? String(minor) : (minor / factor).toFixed(digits);
  return `${major} ${currency}`;
}

export function paymentStatusLabel(status?: string): string {
  switch ((status || "").toUpperCase()) {
    case "PENDING":
      return "待支付";
    case "SUCCEEDED":
      return "已支付";
    case "FAILED":
      return "支付未成功";
    case "PARTIALLY_REFUNDED":
      return "已部分退款";
    case "REFUNDED":
      return "已全额退款";
    case "EXPIRED":
      return "已过期";
    case "CANCELLED":
      return "已取消";
    default:
      return status ? `状态：${status}` : "状态未知";
  }
}

export function formatPaymentTime(raw?: string): string {
  if (!raw) return "";
  const at = Date.parse(raw);
  if (!Number.isFinite(at)) return raw;
  const d = new Date(at);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function shortPaymentId(paymentId?: string): string {
  const id = (paymentId || "").trim();
  if (id.length <= 12) return id;
  return `${id.slice(0, 8)}…`;
}

/** Friendly copy for payment HTTP errors — 支付接口错误的面向用户说明. */
export function paymentMessage(err: unknown): string {
  if (err instanceof PlatformHttpError) {
    switch (err.reason) {
      case "payment-cancel-not-pending":
        return "这份单据已经不是待支付，不能取消";
      case "payment-not-found":
        return "没有找到这份支付单";
      case "payment-cross-tenant-denied":
        return "当前账号无法访问该组织的支付单";
      case "payment-cancel-denied":
        return "只能取消您本人的待支付单据";
      case "payment-charge-denied":
        return "只能对您本人的待支付单据发起扣款";
      case "payment-charge-not-pending":
        return "这份单据已经不是待支付，不能发起扣款";
      case "payment-idempotency-conflict":
        return "这个请求已经用过，但内容和上次不一样";
      case "payment-adapter-not-configured":
        return "当前组织尚未配置可用的支付通道，无法发起扣款";
      case "payment-adapter-send-failed":
        return "支付通道暂时无法发起扣款，请稍后重试";
      case "end-user-tenant-access-denied":
        return "当前账号无法访问该组织的内容";
      default:
        break;
    }
    if (err.status === 401) return "登录已过期，请重新登录";
    if (err.status === 403) return "没有权限完成这项操作";
    if (err.status === 404) return "没有找到这份支付单";
    if (err.status === 409) return "这份单据目前不能这样操作";
    if (err.status === 503) return "当前组织尚未配置可用的支付通道，无法发起扣款";
    return "操作没有成功，请稍后重试";
  }
  if (err instanceof Error && err.message && !err.message.startsWith("platform ")) {
    return err.message;
  }
  return "出了一点问题，请稍后重试";
}
