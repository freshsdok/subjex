import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  PAGE_CONSENTS,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";

/** Current grant row from GET /api/v1/consents — 当前授予行. */
type CurrentConsentRow = {
  tenantId?: string;
  subjectId?: string;
  purposeKey?: string;
  consentVersionId?: string;
  eventId?: string;
  grantedAt?: string;
};

/** History row from GET /api/v1/consents/history — 历史行. */
type HistoryConsentRow = {
  eventId?: string;
  tenantId?: string;
  subjectId?: string;
  purposeKey?: string;
  consentVersionId?: string;
  eventKind?: string;
  actorIdentityId?: string;
  occurredAt?: string;
};

type SearchBag = {
  tenantId?: string;
  subjectId?: string;
  purposeKey?: string;
  from?: string;
  to?: string;
  view?: string;
};

/**
 * Consent records page — read-only operator list of purpose-consent grants and history.
 * Enter: Manifest {@code page.consents} ({@code consent.read}). Operators cannot grant or withdraw.
 * <p>
 * 同意记录页：操作员只读查看目的同意与撤回历史。进入走 page.consents（consent.read）。不能代用户同意或撤回。
 */
export default async function ConsentsPage({
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
  if (!pageAllowed(manifest, PAGE_CONSENTS, permissions.has("consent.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="consent.read" />;
  }

  const jar = await cookies();
  const cookieTenant = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  const tenantId = first(params.tenantId) || cookieTenant;
  const subjectId = first(params.subjectId);
  const purposeKey = first(params.purposeKey);
  const fromRaw = first(params.from);
  const toRaw = first(params.to);
  const viewHistory = first(params.view) === "history";

  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });

  const form = (
    <form
      method="get"
      action="/consents"
      className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
    >
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsTenantLabel}</span>
        <input
          name="tenantId"
          type="text"
          defaultValue={tenantId}
          placeholder={phrases.consentsTenantPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsSubjectLabel}</span>
        <input
          name="subjectId"
          type="text"
          defaultValue={subjectId}
          placeholder={phrases.consentsSubjectPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsPurposeLabel}</span>
        <input
          name="purposeKey"
          type="text"
          defaultValue={purposeKey}
          placeholder={phrases.consentsPurposePlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsFilterFromLabel}</span>
        <input
          name="from"
          type="datetime-local"
          defaultValue={toDatetimeLocalValue(fromRaw)}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsFilterToLabel}</span>
        <input
          name="to"
          type="datetime-local"
          defaultValue={toDatetimeLocalValue(toRaw)}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[12rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.consentsEventKindLabel}</span>
        <select
          name="view"
          defaultValue={viewHistory ? "history" : "current"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="current">{phrases.consentsViewCurrent}</option>
          <option value="history">{phrases.consentsViewHistory}</option>
        </select>
      </label>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.consentsSearchAction}
        </button>
        <Link href="/consents" className="rounded-md border border-border px-3 py-1.5 hover:bg-background">
          {phrases.consentsClearAction}
        </Link>
      </div>
    </form>
  );

  if (!tenantId) {
    return (
      <section>
        <PageHeading title={phrases.consentsTitle} hint={phrases.consentsHint} />
        {form}
        <p className="text-sm text-muted">{phrases.consentsMissingTenant}</p>
      </section>
    );
  }

  const query = new URLSearchParams();
  if (subjectId) query.set("subjectId", subjectId);
  if (purposeKey) query.set("purposeKey", purposeKey);
  const fromIsoValue = toIsoInstant(fromRaw);
  const toIsoValue = toIsoInstant(toRaw);
  if (fromIsoValue) query.set("from", fromIsoValue);
  if (toIsoValue) query.set("to", toIsoValue);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const path = viewHistory ? `consents/history${suffix}` : `consents${suffix}`;
  const read = viewHistory
    ? await readPlatform<HistoryConsentRow[]>(path, { tenantId })
    : await readPlatform<CurrentConsentRow[]>(path, { tenantId });

  if (read.status === 403) {
    return (
      <section>
        <PageHeading title={phrases.consentsTitle} hint={phrases.consentsHint} />
        {form}
        <ForbiddenNotice phrases={phrases} permission="consent.read" />
      </section>
    );
  }
  if (!read.body) {
    return (
      <section>
        <PageHeading title={phrases.consentsTitle} hint={phrases.consentsHint} />
        {form}
        <LoadFailedNotice phrases={phrases} status={read.status} />
      </section>
    );
  }

  const rows = read.body;
  return (
    <section>
      <PageHeading title={phrases.consentsTitle} hint={phrases.consentsHint} />
      {form}
      {rows.length === 0 ? (
        <p className="text-sm text-muted">{phrases.consentsEmpty}</p>
      ) : viewHistory ? (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.consentsOccurredAtLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsSubjectLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsPurposeLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsVersionLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsEventKindLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsActorLabel}</th>
            </tr>
          </thead>
          <tbody>
            {(rows as HistoryConsentRow[]).map((row) => (
              <tr key={row.eventId} className="border-t border-border">
                <td className="whitespace-nowrap px-3 py-2">
                  {row.occurredAt ? timeFormat.format(new Date(row.occurredAt)) : ""}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{row.subjectId}</td>
                <td className="px-3 py-2">{row.purposeKey}</td>
                <td className="px-3 py-2 font-mono text-xs">{row.consentVersionId}</td>
                <td className="px-3 py-2">
                  {row.eventKind === "WITHDRAW" ? phrases.consentsStatusWithdrawn : phrases.consentsStatusGranted}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{row.actorIdentityId}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.consentsGrantedAtLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsSubjectLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsPurposeLabel}</th>
              <th className="px-3 py-2 font-medium">{phrases.consentsVersionLabel}</th>
            </tr>
          </thead>
          <tbody>
            {(rows as CurrentConsentRow[]).map((row) => (
              <tr key={`${row.subjectId}:${row.purposeKey}:${row.eventId}`} className="border-t border-border">
                <td className="whitespace-nowrap px-3 py-2">
                  {row.grantedAt ? timeFormat.format(new Date(row.grantedAt)) : ""}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{row.subjectId}</td>
                <td className="px-3 py-2">{row.purposeKey}</td>
                <td className="px-3 py-2 font-mono text-xs">{row.consentVersionId}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}

/** Parse a form or ISO timestamp to ISO-8601 for the API — 把表单或 ISO 时间转成接口用的 ISO-8601. */
function toIsoInstant(value: string): string {
  if (!value) return "";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  return parsed.toISOString();
}

/** Format a timestamp for {@code datetime-local} inputs — 把时间格式化成 datetime-local 输入值. */
function toDatetimeLocalValue(value: string): string {
  if (!value) return "";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}
