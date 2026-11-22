import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  filterExportQueueRows,
  sortExportQueueRows,
  type ExportQueueRow,
} from "@/lib/export-queue";
import {
  BUTTON_EXPORTS_APPROVE,
  PAGE_EXPORTS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { ExportQueueTable } from "./export-queue-table";

type SearchBag = {
  tenantId?: string;
  status?: string;
};

/**
 * Export request queue — list, approve/reject (not self), generate, and short-lived signed download.
 * Enter: Manifest {@code page.exports} ({@code export.request} or {@code export.approve}).
 * Decide / generate / download: {@code btn.exports.approve} ({@code export.approve}).
 * <p>
 * 导出申请队列：列表、批准/驳回（不能审自己的）、生成，以及短时签名下载。
 * 进入走 page.exports；审批/生成/下载走 btn.exports.approve。
 */
export default async function ExportsPage({
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
  const canEnter = permissions.has("export.request") || permissions.has("export.approve");
  if (!pageAllowed(manifest, PAGE_EXPORTS, canEnter)) {
    return <ForbiddenNotice phrases={phrases} permission="export.request | export.approve" />;
  }
  const canApprove = buttonAllowed(
    manifest,
    BUTTON_EXPORTS_APPROVE,
    permissions.has("export.approve"),
  );

  const jar = await cookies();
  const cookieTenant = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  const tenantId = first(params.tenantId) || cookieTenant;
  const statusFilter = first(params.status);

  const form = (
    <form
      method="get"
      action="/exports"
      className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
    >
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.exportsTenantLabel}</span>
        <input
          name="tenantId"
          type="text"
          defaultValue={tenantId}
          placeholder={phrases.exportsTenantPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.exportsStatusLabel}</span>
        <select
          name="status"
          defaultValue={statusFilter || "all"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="all">{phrases.exportsStatusAll}</option>
          <option value="REQUESTED">{phrases.exportsStatusRequested}</option>
          <option value="APPROVED">{phrases.exportsStatusApproved}</option>
          <option value="REJECTED">{phrases.exportsStatusRejected}</option>
          <option value="READY">{phrases.exportsStatusReady}</option>
          <option value="EXPIRED">{phrases.exportsStatusExpired}</option>
        </select>
      </label>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.exportsSearchAction}
        </button>
        <Link href="/exports" className="rounded-md border border-border px-3 py-1.5 hover:bg-background">
          {phrases.exportsClearAction}
        </Link>
      </div>
    </form>
  );

  return (
    <section>
      <PageHeading title={phrases.exportsTitle} hint={phrases.exportsHint} />
      {form}
      {!canApprove ? <p className="mb-3 text-sm text-muted">{phrases.exportsApproveRequired}</p> : null}
      {!tenantId ? (
        <p className="text-sm text-muted">{phrases.exportsMissingTenant}</p>
      ) : (
        <QueueBody
          tenantId={tenantId}
          statusFilter={statusFilter}
          operatorSubjectId={me?.subjectId ?? ""}
          canApprove={canApprove}
          phrases={phrases}
          language={language}
        />
      )}
    </section>
  );
}

async function QueueBody({
  tenantId,
  statusFilter,
  operatorSubjectId,
  canApprove,
  phrases,
  language,
}: {
  tenantId: string;
  statusFilter: string;
  operatorSubjectId: string;
  canApprove: boolean;
  phrases: Awaited<ReturnType<typeof currentLanguage>>["phrases"];
  language: "zh" | "en";
}) {
  const read = await readPlatform<ExportQueueRow[]>("exports", { tenantId });
  if (read.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="export.request | export.approve" />;
  }
  if (!read.body) {
    return <LoadFailedNotice phrases={phrases} status={read.status} />;
  }
  const initialRows = sortExportQueueRows(filterExportQueueRows(read.body, statusFilter));
  return (
    <ExportQueueTable
      key={`${tenantId}:${statusFilter}`}
      initialRows={initialRows}
      statusFilter={statusFilter}
      tenantId={tenantId}
      operatorSubjectId={operatorSubjectId}
      canApprove={canApprove}
      phrases={phrases}
      language={language}
    />
  );
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}
