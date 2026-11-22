"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { type PhraseBook } from "@/i18n/phrases";
import {
  datetimeLocalToIso,
  datetimeLocalValue,
  hasRecordedEvents,
  sharesConsoleQuery,
  type ShareCampaignRow,
  type ShareDailyRow,
  type ShareResourceRow,
  type ShareSharerRow,
  type ShareTotals,
} from "@/lib/shares-observation";

/**
 * Tenant-scoped share/open tables from recorded events only (no sample charts).
 * 按租户展示已记录的分享/打开表格（不用示例图充数）。
 */
export function SharesConsole({
  phrases,
  tenants,
  selectedTenantId,
  selectedFrom,
  selectedTo,
  problem,
  totals,
  resources,
  daily,
  sharers,
  campaigns,
}: {
  phrases: PhraseBook;
  tenants: Array<{ tenantId: string; tenantName: string }>;
  selectedTenantId: string;
  selectedFrom: string;
  selectedTo: string;
  problem: "sharesForbidden" | "sharesLoadFailed" | null;
  totals: ShareTotals | null;
  resources: ShareResourceRow[];
  daily: ShareDailyRow[];
  sharers: ShareSharerRow[];
  campaigns: ShareCampaignRow[];
}) {
  const router = useRouter();
  const [tenantId, setTenantId] = useState(selectedTenantId);
  const [from, setFrom] = useState(datetimeLocalValue(selectedFrom));
  const [to, setTo] = useState(datetimeLocalValue(selectedTo));

  function lookup(event: FormEvent) {
    event.preventDefault();
    router.push(sharesConsoleQuery(tenantId, datetimeLocalToIso(from), datetimeLocalToIso(to)));
  }

  const empty = selectedTenantId && !problem && !hasRecordedEvents(totals);

  return (
    <div className="space-y-6">
      <form onSubmit={lookup} className="flex flex-wrap items-end gap-2 text-xs">
        <label className="flex flex-col gap-1">
          {phrases.sharesTenantLabel}
          <select
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1"
          >
            <option value="">{phrases.sharesTenantPlaceholder}</option>
            {tenants.map((tenant) => (
              <option key={tenant.tenantId} value={tenant.tenantId}>
                {tenant.tenantName ? `${tenant.tenantName} (${tenant.tenantId})` : tenant.tenantId}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          {phrases.sharesFromLabel}
          <input
            type="datetime-local"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1">
          {phrases.sharesToLabel}
          <input
            type="datetime-local"
            value={to}
            onChange={(event) => setTo(event.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1"
          />
        </label>
        <button
          type="submit"
          className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-60"
          disabled={!tenantId.trim()}
        >
          {phrases.sharesSearchAction}
        </button>
      </form>

      {!selectedTenantId ? (
        <p className="text-sm text-muted">{phrases.sharesPickTenant}</p>
      ) : problem ? (
        <p className="text-sm text-muted">{phrases[problem]}</p>
      ) : empty ? (
        <p className="text-sm text-muted">{phrases.sharesEmpty}</p>
      ) : (
        <>
          <section>
            <h2 className="text-sm font-medium">{phrases.sharesTotalsTitle}</h2>
            <p className="mt-2 text-sm">
              {phrases.sharesShareCountLabel}：{totals?.shares ?? 0}　
              {phrases.sharesOpenCountLabel}：{totals?.opens ?? 0}
            </p>
          </section>

          <section>
            <h2 className="text-sm font-medium">{phrases.sharesResourcesTitle}</h2>
            <CountTable
              headers={[
                phrases.sharesResourceTypeColumn,
                phrases.sharesResourceIdColumn,
                phrases.sharesShareCountLabel,
                phrases.sharesOpenCountLabel,
              ]}
              rows={resources.map((row) => [
                row.resourceType ?? "",
                row.resourceId ?? "",
                String(row.shares ?? 0),
                String(row.opens ?? 0),
              ])}
            />
          </section>

          <section>
            <h2 className="text-sm font-medium">{phrases.sharesDailyTitle}</h2>
            <CountTable
              headers={[
                phrases.sharesDayColumn,
                phrases.sharesShareCountLabel,
                phrases.sharesOpenCountLabel,
              ]}
              rows={daily.map((row) => [row.day ?? "", String(row.shares ?? 0), String(row.opens ?? 0)])}
            />
          </section>

          <section>
            <h2 className="text-sm font-medium">{phrases.sharesSharersTitle}</h2>
            <CountTable
              headers={[phrases.sharesSharerColumn, phrases.sharesShareCountLabel]}
              rows={sharers.map((row) => [
                row.displayName?.trim()
                  || row.actorSubjectId
                  || phrases.sharesAnonymousSharer,
                String(row.shares ?? 0),
              ])}
            />
          </section>

          <section>
            <h2 className="text-sm font-medium">{phrases.sharesCampaignsTitle}</h2>
            {campaigns.length === 0 ? (
              <p className="mt-2 text-sm text-muted">{phrases.sharesCampaignsEmpty}</p>
            ) : (
              <CountTable
                headers={[
                  phrases.sharesCampaignColumn,
                  phrases.sharesUtmSourceColumn,
                  phrases.sharesUtmMediumColumn,
                  phrases.sharesUtmCampaignColumn,
                  phrases.sharesUtmContentColumn,
                  phrases.sharesUtmTermColumn,
                  phrases.sharesShareCountLabel,
                  phrases.sharesOpenCountLabel,
                ]}
                rows={campaigns.map((row) => [
                  row.campaign ?? "",
                  row.utmSource ?? "",
                  row.utmMedium ?? "",
                  row.utmCampaign ?? "",
                  row.utmContent ?? "",
                  row.utmTerm ?? "",
                  String(row.shares ?? 0),
                  String(row.opens ?? 0),
                ])}
              />
            )}
          </section>
        </>
      )}
    </div>
  );
}

function CountTable({ headers, rows }: { headers: string[]; rows: string[][] }) {
  return (
    <table className="mt-3 w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
      <thead className="bg-background text-left text-muted">
        <tr>
          {headers.map((header) => (
            <th key={header} className="px-3 py-2 font-medium">
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index} className="border-t border-border">
            {row.map((cell, cellIndex) => (
              <td key={cellIndex} className="px-3 py-2 font-mono text-xs">
                {cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
