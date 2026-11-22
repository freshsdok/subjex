import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  apiPaymentStatusFilter,
  formatPaymentAmount,
  paymentStatusPhraseKey,
  type PaymentDocumentView,
} from "@/lib/payments-console";
import {
  PAGE_PAYMENTS,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";

type SearchBag = {
  tenantId?: string;
  status?: string;
  payerSubjectId?: string;
};

/**
 * Payment and refund status — read-only operator list.
 * Enter: Manifest {@code page.payments} ({@code payment.read}). No create, capture, or refund actions.
 * <p>
 * 支付与退款状态：操作员只读列表。进入走 page.payments（payment.read）。不能收款、确认到账或办理退款。
 */
export default async function PaymentsPage({
  searchParams,
}: {
  searchParams: Promise<SearchBag>;
}) {
  const { language, phrases } = await currentLanguage();
  const params = await searchParams;
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_PAYMENTS, permissions.has("payment.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="payment.read" />;
  }

  const jar = await cookies();
  const cookieTenant = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  const tenantId = first(params.tenantId) || cookieTenant;
  const statusFilter = first(params.status);
  const payerSubjectId = first(params.payerSubjectId);
  const form = (
    <FilterForm
      phrases={phrases}
      tenantId={tenantId}
      statusFilter={statusFilter}
      payerSubjectId={payerSubjectId}
    />
  );

  return (
    <section>
      <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
      {form}
      {!tenantId ? (
        <p className="text-sm text-muted">{phrases.paymentsMissingTenant}</p>
      ) : (
        <ListBody
          tenantId={tenantId}
          statusFilter={statusFilter}
          payerSubjectId={payerSubjectId}
          phrases={phrases}
          language={language}
        />
      )}
    </section>
  );
}

function FilterForm({
  phrases,
  tenantId,
  statusFilter,
  payerSubjectId,
}: {
  phrases: PhraseBook;
  tenantId: string;
  statusFilter: string;
  payerSubjectId: string;
}) {
  return (
    <form
      method="get"
      action="/payments"
      className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
    >
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.paymentsTenantLabel}</span>
        <input
          name="tenantId"
          type="text"
          defaultValue={tenantId}
          placeholder={phrases.paymentsTenantPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.paymentsStatusLabel}</span>
        <select
          name="status"
          defaultValue={apiPaymentStatusFilter(statusFilter) || "all"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="all">{phrases.paymentsStatusAll}</option>
          <option value="PENDING">{phrases.paymentsStatusPending}</option>
          <option value="SUCCEEDED">{phrases.paymentsStatusSucceeded}</option>
          <option value="FAILED">{phrases.paymentsStatusFailed}</option>
          <option value="PARTIALLY_REFUNDED">{phrases.paymentsStatusPartiallyRefunded}</option>
          <option value="REFUNDED">{phrases.paymentsStatusRefunded}</option>
        </select>
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.paymentsPayerLabel}</span>
        <input
          name="payerSubjectId"
          type="text"
          defaultValue={payerSubjectId}
          placeholder={phrases.paymentsPayerPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.paymentsSearchAction}
        </button>
        <Link href="/payments" className="rounded-md border border-border px-3 py-1.5 hover:bg-background">
          {phrases.paymentsClearAction}
        </Link>
      </div>
    </form>
  );
}

async function ListBody({
  tenantId,
  statusFilter,
  payerSubjectId,
  phrases,
  language,
}: {
  tenantId: string;
  statusFilter: string;
  payerSubjectId: string;
  phrases: PhraseBook;
  language: "zh" | "en";
}) {
  const query = new URLSearchParams();
  const status = apiPaymentStatusFilter(statusFilter);
  if (status) query.set("status", status);
  if (payerSubjectId) query.set("payerSubjectId", payerSubjectId);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const read = await readPlatform<PaymentDocumentView[]>(`payments${suffix}`, { tenantId });
  if (read.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="payment.read" />;
  }
  if (!read.body) {
    return <LoadFailedNotice phrases={phrases} status={read.status} />;
  }
  const rows = read.body;
  if (rows.length === 0) {
    return <p className="text-sm text-muted">{phrases.paymentsEmpty}</p>;
  }
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "short",
  });
  const locale = language === "zh" ? "zh-CN" : "en";
  const listQuery = new URLSearchParams();
  listQuery.set("tenantId", tenantId);
  if (statusFilter) listQuery.set("status", statusFilter);
  if (payerSubjectId) listQuery.set("payerSubjectId", payerSubjectId);
  const listQs = listQuery.toString();
  return (
    <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
      <thead className="bg-background text-left text-muted">
        <tr>
          <th className="px-3 py-2 font-medium">{phrases.paymentsCreatedAtLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.paymentsStatusLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.paymentsAmountLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.paymentsRefundedAmountLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.paymentsPayerLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.paymentsReferenceLabel}</th>
          <th className="px-3 py-2 font-medium" />
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const statusKey = paymentStatusPhraseKey(row.status);
          const detailHref = `/payments/${encodeURIComponent(row.paymentId)}${listQs ? `?${listQs}` : ""}`;
          return (
            <tr key={row.paymentId} className="border-t border-border">
              <td className="whitespace-nowrap px-3 py-2">
                {row.createdAt ? timeFormat.format(new Date(row.createdAt)) : ""}
              </td>
              <td className="px-3 py-2">{statusKey ? phrases[statusKey] : row.status}</td>
              <td className="px-3 py-2">{formatPaymentAmount(row.amount, locale)}</td>
              <td className="px-3 py-2">{formatPaymentAmount(row.refundedAmount, locale)}</td>
              <td className="px-3 py-2">
                {payerLabel(phrases, row.payerRealm)}
                {row.payerSubjectId ? (
                  <span className="ml-1 font-mono text-xs">{row.payerSubjectId}</span>
                ) : null}
              </td>
              <td className="px-3 py-2">{row.reference || ""}</td>
              <td className="px-3 py-2">
                <Link href={detailHref} className="text-accent hover:underline">
                  {phrases.paymentsDetailAction}
                </Link>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function payerLabel(phrases: PhraseBook, realm?: string | null): string {
  const value = (realm || "").trim().toUpperCase();
  if (value === "END_USER") return phrases.paymentsPayerEndUser;
  if (value === "OPERATOR") return phrases.paymentsPayerOperator;
  return value ? value : "";
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}
