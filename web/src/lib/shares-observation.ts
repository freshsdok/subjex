/**
 * Helpers for the operator shares-and-opens observation page.
 * 操作员分享与打开观察页的辅助方法。
 */

export type ShareTotals = { shares?: number; opens?: number };
export type ShareResourceRow = {
  resourceType?: string;
  resourceId?: string;
  shares?: number;
  opens?: number;
};
export type ShareDailyRow = { day?: string; shares?: number; opens?: number };
export type ShareSharerRow = {
  actorSubjectId?: string | null;
  displayName?: string | null;
  shares?: number;
};
export type ShareCampaignRow = {
  campaign?: string | null;
  utmSource?: string | null;
  utmMedium?: string | null;
  utmCampaign?: string | null;
  utmContent?: string | null;
  utmTerm?: string | null;
  shares?: number;
  opens?: number;
};

export function sharesConsoleQuery(tenantId: string, from?: string, to?: string): string {
  const params = new URLSearchParams();
  const tid = tenantId.trim();
  if (tid) {
    params.set("tenantId", tid);
  }
  if (from && from.trim()) {
    params.set("from", from.trim());
  }
  if (to && to.trim()) {
    params.set("to", to.trim());
  }
  const encoded = params.toString();
  return encoded ? `/shares?${encoded}` : "/shares";
}

export function hasRecordedEvents(totals?: ShareTotals | null): boolean {
  return (totals?.shares ?? 0) + (totals?.opens ?? 0) > 0;
}

export function datetimeLocalValue(iso?: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function datetimeLocalToIso(value: string): string | undefined {
  const raw = value.trim();
  if (!raw) return undefined;
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return undefined;
  return date.toISOString();
}
