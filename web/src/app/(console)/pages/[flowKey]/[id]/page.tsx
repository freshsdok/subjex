import Link from "next/link";
import { DetailReadonly, EntityBlobPanel, Section, Tabs, WorkflowStatusPanel } from "@/components/page-blocks";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  isGenericRecordsCollectionPath,
  itemsFromBody,
  platformPathFromApi,
  recordDetailPlatformPath,
} from "@/lib/page-flow";
import {
  PAGE_PAGES,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readDeclarationTenantId, readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  entityKey?: string | null;
  detail?: { apiPath?: string; itemsKey?: string | null; idField?: string; blocks?: string[] };
};

// Declared detail page — 声明式详情页：优先 GET /records/{id}，否则列表里按 idField 挑一项。
// detail.blocks: Tabs → Fields/Raw shell; Section → card wrap. Omitted → prior layout.
export default async function DeclaredDetailPage({
  params,
}: {
  params: Promise<{ flowKey: string; id: string }>;
}) {
  const { flowKey, id } = await params;
  const { language, phrases } = await currentLanguage();
  const tenantId = await readDeclarationTenantId();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_PAGES, permissions.has("page.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  const flowRead = await readPlatform<PageFlowDocument>(`pages/${encodeURIComponent(flowKey)}`, {
    tenantId,
  });
  if (flowRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!flowRead.body?.detail?.apiPath || !flowRead.body.detail.idField) {
    return <LoadFailedNotice phrases={phrases} status={flowRead.status || 404} />;
  }
  const detail = flowRead.body.detail;
  let row: Record<string, unknown> | undefined;

  if (isGenericRecordsCollectionPath(detail.apiPath!)) {
    const oneRead = await readPlatform<Record<string, unknown>>(
      recordDetailPlatformPath(detail.apiPath!, id),
      { tenantId },
    );
    if (
      oneRead.status === 200 &&
      oneRead.body &&
      typeof oneRead.body === "object" &&
      !Array.isArray(oneRead.body)
    ) {
      row = oneRead.body;
    }
  }

  if (!row) {
    const listRead = await readPlatform<unknown>(platformPathFromApi(detail.apiPath!), {
      tenantId,
    });
    if (listRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
    if (listRead.status >= 400) return <LoadFailedNotice phrases={phrases} status={listRead.status} />;
    const rows = itemsFromBody(listRead.body, detail.itemsKey);
    row = rows.find((entry) => String(entry[detail.idField!]) === id);
  }

  if (!row) return <LoadFailedNotice phrases={phrases} status={404} />;
  const title = language === "zh" ? flowRead.body.titleZh : flowRead.body.titleEn;
  const blocks = detail.blocks ?? [];
  const showTabs = blocks.includes("Tabs");
  const showSection = blocks.includes("Section");

  const fieldsView = <DetailReadonly record={row} />;
  const detailBody = showTabs ? (
    <Tabs
      emptyNote={phrases.tabsEmptyNote}
      ariaLabel={phrases.detailSectionTitle}
      tabs={[
        { id: "fields", label: phrases.detailTabFields, content: fieldsView },
        {
          id: "raw",
          label: phrases.detailTabRaw,
          content: (
            <pre className="max-w-2xl overflow-auto rounded-lg border border-border bg-background p-4 font-mono text-xs">
              {JSON.stringify(row, null, 2)}
            </pre>
          ),
        },
      ]}
    />
  ) : (
    fieldsView
  );

  return (
    <section>
      <PageHeading title={title ?? flowKey} hint={phrases.pagesDetailHint} />
      <p className="mb-4 text-sm">
        <Link href={`/pages/${encodeURIComponent(flowKey)}`} className="text-accent underline">
          {phrases.backToListAction}
        </Link>
      </p>
      {showSection ? (
        <Section title={phrases.detailSectionTitle}>{detailBody}</Section>
      ) : (
        detailBody
      )}
      {/* SHARED-3: blob panel when flow declares entityKey — 流程声明了 entityKey 时显示附件面板 */}
      {flowRead.body.entityKey ? (
        <EntityBlobPanel
          entityKey={flowRead.body.entityKey}
          recordId={id}
          phrases={phrases}
          tenantId={tenantId}
        />
      ) : null}
      {/* WF-3: workflow status for any declared entityKey (repair-ticket FSM binding). */}
      {/* WF-3：凡声明了 entityKey 的详情均可挂报修工单状态机。 */}
      {flowRead.body.entityKey ? (
        <WorkflowStatusPanel
          entityKey={flowRead.body.entityKey}
          recordId={id}
          phrases={phrases}
          tenantId={tenantId}
        />
      ) : null}
    </section>
  );
}
