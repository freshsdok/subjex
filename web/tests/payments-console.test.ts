import { describe, expect, it } from "vitest";
import {
  apiPaymentStatusFilter,
  currencyFractionDigits,
  formatPaymentAmount,
  paymentQueryErrorPhraseKey,
  paymentStatusPhraseKey,
} from "@/lib/payments-console";

describe("payments-console", () => {
  it("maps statuses and error reasons to phrase keys — 状态与错误原因映射到文案键", () => {
    expect(paymentStatusPhraseKey("pending")).toBe("paymentsStatusPending");
    expect(paymentStatusPhraseKey("PARTIALLY_REFUNDED")).toBe("paymentsStatusPartiallyRefunded");
    expect(paymentStatusPhraseKey("unknown")).toBeUndefined();
    expect(paymentQueryErrorPhraseKey("payment-not-found")).toBe("paymentsNotFound");
    expect(paymentQueryErrorPhraseKey("payment-cross-tenant-denied")).toBe("paymentsCrossTenantForbidden");
    expect(paymentQueryErrorPhraseKey("other")).toBeUndefined();
  });

  it("sends only known status filters to the list API — 仅把已知状态筛选传给列表接口", () => {
    expect(apiPaymentStatusFilter("all")).toBe("");
    expect(apiPaymentStatusFilter("")).toBe("");
    expect(apiPaymentStatusFilter("SUCCEEDED")).toBe("SUCCEEDED");
    expect(apiPaymentStatusFilter("nope")).toBe("");
  });

  it("formats minor units with the currency's fraction digits — 按币种小数位格式化最小单位", () => {
    expect(currencyFractionDigits("CNY")).toBe(2);
    expect(currencyFractionDigits("JPY")).toBe(0);
    expect(formatPaymentAmount({ amountMinor: 199, currency: "CNY" }, "zh-CN")).toMatch(/1\.99/);
    expect(formatPaymentAmount({ amountMinor: 199, currency: "JPY" }, "en")).toMatch(/199/);
    expect(formatPaymentAmount({ amountMinor: 0, currency: "CNY" }, "en")).toMatch(/0/);
    expect(formatPaymentAmount(undefined, "en")).toBe("");
  });
});
