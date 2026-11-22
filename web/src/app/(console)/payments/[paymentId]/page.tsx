import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieName } from "@/lib/org-console";
import {
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
 * Payment detail — read-only amounts, payer, and refund rows.
 * Enter: Manifest {@code page.payments} ({@code payment.read}). No capture or refund actions.
 * <p>
 * 支付详情：只读展示金额、付款人和退款行。进入走 page.payments。不能确认到账或办理退款。
 */
export default async function PaymentDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ paymentId: string }>;
  searchParams: Promise<SearchBag>;
}) {
  const { language, phrases } = await currentLanguage();
  const { paymentId: rawId } = await params;
  const query = await searchParams;
  const paymentId = decodeURIComponent(rawId || "").trim();
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
  const tenantId = first(query.tenantId) || cookieTenant;
  const backQuery = new URLSearchParams();
  if (tenantId) backQuery.set("tenantId", tenantId);
  if (first(query.status)) backQuery.set("status", first(query.status));
  if (first(query.payerSubjectId)) backQuery.set("payerSubjectId", first(query.payerSubjectId));
  const backHref = `/payments${backQuery.toString() ? `?${backQuery.toString()}` : ""}`;
  const backLink = (
    <p className="mb-4 text-sm">
      <Link href={backHref} className="text-accent hover:underline">
        {phrases.paymentsBackToList}
      </Link>
    </p>
  );

  if (!tenantId) {
    return (
      <section>
        <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.paymentsMissingTenant}</p>
      </section>
    );
  }
  if (!paymentId) {
    return (
      <section>
        <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.paymentsNotFound}</p>
      </section>
    );
  }

  const read = await readPlatform<PaymentDocumentView>(`payments/${encodeURIComponent(paymentId)}`, {
    tenantId,
  });
  if (read.status === 403) {
    return (
      <section>
        <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
        {backLink}
        <ForbiddenNotice phrases={phrases} permission="payment.read" />
      </section>
    );
  }
  if (read.status === 404) {
    return (
      <section>
        <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.paymentsNotFound}</p>
      </section>
    );
  }
  if (!read.body) {
    return (
      <section>
        <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
        {backLink}
        <LoadFailedNotice phrases={phrases} status={read.status} />
      </section>
    );
  }

  const doc = read.body;
  const locale = language === "zh" ? "zh-CN" : "en";
  const timeFormat = new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "medium" });
  const statusKey = paymentStatusPhraseKey(doc.status);
  const refunds = doc.refunds ?? [];
  return (
    <section>
      <PageHeading title={phrases.paymentsTitle} hint={phrases.paymentsHint} />
      {backLink}
      <dl className="mb-6 grid gap-3 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2">
        <Detail itemLabel={phrases.paymentsPaymentIdLabel} value={doc.paymentId} mono />
        <Detail itemLabel={phrases.paymentsStatusLabel} value={statusKey ? phrases[statusKey] : doc.status || ""} />
        <Detail itemLabel={phrases.paymentsAmountLabel} value={formatPaymentAmount(doc.amount, locale)} />
        <Detail
          itemLabel={phrases.paymentsRefundedAmountLabel}
          value={formatPaymentAmount(doc.refundedAmount, locale)}
        />
        <Detail
          itemLabel={phrases.paymentsPayerLabel}
          value={payerLine(phrases, doc.payerRealm, doc.payerSubjectId)}
        />
        <Detail itemLabel={phrases.paymentsReferenceLabel} value={doc.reference || ""} />
        <Detail itemLabel={phrases.paymentsProviderLabel} value={doc.providerId || ""} />
        <Detail
          itemLabel={phrases.paymentsCreatedAtLabel}
          value={doc.createdAt ? timeFormat.format(new Date(doc.createdAt)) : ""}
        />
        <Detail
          itemLabel={phrases.paymentsUpdatedAtLabel}
          value={doc.updatedAt ? timeFormat.format(new Date(doc.updatedAt)) : ""}
        />
      </dl>
      <h2 className="mb-2 text-base font-medium">{phrases.paymentsRefundsTitle}</h2>
      {refunds.length === 0 ? (
        <p className="text-sm text-muted">{phrases.paymentsRefundEmpty}</p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.paymentsCreatedAtLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.paymentsRefundIdLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.paymentsAmountLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.paymentsStatusLabel}</th>
            </tr>
          </thead>
          <tbody>
            {refunds.map((row) => {
              const refundStatusKey = paymentStatusPhraseKey(row.refundStatus);
              return (
                <tr key={row.refundId} className="border-t border-border">
                  <td className="whitespace-nowrap px-3 py-2">
                    {row.createdAt ? timeFormat.format(new Date(row.createdAt)) : ""}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">{row.refundId}</td>
                  <td className="px-3 py-2">{formatPaymentAmount(row.amount, locale)}</td>
                  <td className="px-3 py-2">
                    {refundStatusKey ? phrases[refundStatusKey] : row.refundStatus || ""}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Detail({
  itemLabel,
  value,
  mono,
}: {
  itemLabel: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-muted">{itemLabel}</dt>
      <dd className={mono ? "font-mono text-xs break-all" : ""}>{value}</dd>
    </div>
  );
}

function payerLine(phrases: PhraseBook, realm?: string | null, subjectId?: string | null): string {
  const value = (realm || "").trim().toUpperCase();
  const who =
    value === "END_USER" ? phrases.paymentsPayerEndUser : value === "OPERATOR" ? phrases.paymentsPayerOperator : value;
  const id = (subjectId || "").trim();
  return [who, id].filter(Boolean).join(" ");
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}
