import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { PAGE_SHARES, pageAllowed, type UiPermissionManifestDocument } from "@/lib/ui-permissions";
import type { ShareCampaignRow, ShareDailyRow, ShareResourceRow, ShareSharerRow, ShareTotals } from "@/lib/shares-observation";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { SharesConsole } from "./shares-console";

type SummaryDocument = {
  tenantId?: string;
  from?: string;
  to?: string;
  totals?: ShareTotals;
  resources?: ShareResourceRow[];
  daily?: ShareDailyRow[];
  sharers?: ShareSharerRow[];
  campaigns?: ShareCampaignRow[];
};

type TenantsDocument = {
  tenants?: Array<{ tenantId?: string; tenantName?: string }>;
};

/**
 * Operator shares-and-opens page — recorded events only; no sample charts or marketing suite.
 * Enter: Manifest {@code page.shares} (resource-event.read).
 * 操作员分享与打开页：只展示已记录事件；无示例图或营销套件。进入走 page.shares。
 */
export default async function SharesPage({
  searchParams,
}: {
  searchParams: Promise<{ tenantId?: string | string[]; from?: string | string[]; to?: string | string[] }>;
}) {
  const { phrases } = await currentLanguage();
  const params = await searchParams;
  const tenantId = first(params.tenantId);
  const from = first(params.from);
  const to = first(params.to);
  let summaryPath = "";
  if (tenantId) {
    const search = new URLSearchParams();
    search.set("tenantId", tenantId);
    if (from) search.set("from", from);
    if (to) search.set("to", to);
    summaryPath = `resource-events/summary?${search.toString()}`;
  }
  const [{ body: me }, uiPermissionsRead, tenantsRead, summaryRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
    readPlatform<TenantsDocument>("tenants"),
    tenantId
      ? readPlatform<SummaryDocument>(summaryPath)
      : Promise.resolve({ status: 200, body: undefined as SummaryDocument | undefined }),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_SHARES, permissions.has("resource-event.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="resource-event.read" />;
  }
  const problem =
    tenantId && summaryRead.status === 403
      ? "sharesForbidden"
      : tenantId && !summaryRead.body
        ? "sharesLoadFailed"
        : null;
  if (!problem && tenantId && summaryRead.status >= 500) {
    return <LoadFailedNotice phrases={phrases} status={summaryRead.status} />;
  }
  const body = summaryRead.body;
  return (
    <section>
      <PageHeading title={phrases.sharesTitle} hint={phrases.sharesHint} />
      <SharesConsole
        phrases={phrases}
        selectedTenantId={tenantId}
        selectedFrom={body?.from ?? from}
        selectedTo={body?.to ?? to}
        problem={problem}
        totals={body?.totals ?? { shares: 0, opens: 0 }}
        resources={body?.resources ?? []}
        daily={body?.daily ?? []}
        sharers={body?.sharers ?? []}
        campaigns={body?.campaigns ?? []}
        tenants={(tenantsRead.body?.tenants ?? [])
          .map((row) => ({ tenantId: row.tenantId ?? "", tenantName: row.tenantName ?? "" }))
          .filter((row) => row.tenantId)}
      />
    </section>
  );
}

function first(value?: string | string[]): string {
  const raw = Array.isArray(value) ? value[0] : value;
  return (raw ?? "").trim();
}
