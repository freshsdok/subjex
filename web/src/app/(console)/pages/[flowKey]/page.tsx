import Link from "next/link";
import { ListFilterBar, ListTable } from "@/components/page-blocks";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  isGenericEntityRecordsPath,
  listFilterFieldOptions,
  parseListFilterSearchParams,
  platformPathWithListFilter,
} from "@/lib/list-filter-query";
import { itemsFromBody, platformPathFromApi } from "@/lib/page-flow";
import { readPlatform } from "@/server/platform-reader";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  formKey?: string | null;
  list?: { path?: string; apiPath?: string; itemsKey?: string | null; columns?: string[]; blocks?: string[] };
  detail?: { idField?: string; blocks?: string[] };
  submit?: { path?: string; blocks?: string[] };
};

// Declared list page — 声明式列表页：按 flow 声明的 apiPath 拉数据，用 ListTable 积木渲染。
// Generic /entities/.../records paths get a thin filter/sort bar via URL searchParams.
// list.blocks is exposed on the page API; empty/omitted still uses ListTable (default layout).
export default async function DeclaredListPage({
  params,
  searchParams,
}: {
  params: Promise<{ flowKey: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { flowKey } = await params;
  const rawSearch = await searchParams;
  const { language, phrases } = await currentLanguage();
  const flowRead = await readPlatform<PageFlowDocument>(`pages/${encodeURIComponent(flowKey)}`);
  if (flowRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!flowRead.body?.list?.apiPath || !flowRead.body.detail?.idField) {
    return <LoadFailedNotice phrases={phrases} status={flowRead.status || 404} />;
  }
  const flow = flowRead.body;
  const apiPath = flow.list!.apiPath!;
  const supportsFilter = isGenericEntityRecordsPath(apiPath);
  const filterParams = supportsFilter ? parseListFilterSearchParams(rawSearch) : {};
  const listBase = platformPathFromApi(apiPath);
  const listPath = supportsFilter ? platformPathWithListFilter(listBase, filterParams) : listBase;
  const listRead = await readPlatform<unknown>(listPath);
  if (listRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (listRead.status >= 400) return <LoadFailedNotice phrases={phrases} status={listRead.status} />;
  const rows = itemsFromBody(listRead.body, flow.list?.itemsKey);
  const idField = flow.detail!.idField!;
  const title = language === "zh" ? flow.titleZh : flow.titleEn;
  const fieldOptions = supportsFilter
    ? listFilterFieldOptions(flow.list?.columns, rows, filterParams)
    : [];
  const listHref = `/pages/${encodeURIComponent(flowKey)}`;
  return (
    <section>
      <PageHeading title={title ?? flowKey} hint={phrases.pagesListHint} />
      <div className="mb-4 flex flex-wrap gap-2 text-sm">
        <Link
          href={`/pages/${encodeURIComponent(flowKey)}/new`}
          className="rounded-md bg-accent px-3 py-1.5 text-white"
        >
          {phrases.newItemAction}
        </Link>
        <Link href="/pages" className="rounded-md border border-border px-3 py-1.5">
          {phrases.backToPagesAction}
        </Link>
      </div>
      {supportsFilter && fieldOptions.length > 0 ? (
        <ListFilterBar
          actionPath={listHref}
          fields={fieldOptions}
          active={filterParams}
          phrases={{
            listFilterFieldLabel: phrases.listFilterFieldLabel,
            listFilterValueLabel: phrases.listFilterValueLabel,
            listSortFieldLabel: phrases.listSortFieldLabel,
            listSortOrderLabel: phrases.listSortOrderLabel,
            listSortOrderAsc: phrases.listSortOrderAsc,
            listSortOrderDesc: phrases.listSortOrderDesc,
            listFilterApplyAction: phrases.listFilterApplyAction,
            listFilterClearAction: phrases.listFilterClearAction,
            listFilterAnyField: phrases.listFilterAnyField,
          }}
        />
      ) : null}
      <ListTable
        rows={rows}
        idField={idField}
        columns={flow.list?.columns}
        detailHref={(id) => `/pages/${encodeURIComponent(flowKey)}/${encodeURIComponent(id)}`}
        phrases={{ emptyList: phrases.emptyList, openDetailAction: phrases.openDetailAction }}
      />
    </section>
  );
}
