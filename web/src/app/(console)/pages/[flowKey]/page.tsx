import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { itemsFromBody, platformPathFromApi } from "@/lib/page-flow";
import { readPlatform } from "@/server/platform-reader";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  formKey?: string | null;
  list?: { path?: string; apiPath?: string; itemsKey?: string | null };
  detail?: { idField?: string };
  submit?: { path?: string };
};

// Declared list page — 声明式列表页：按 flow 声明的 apiPath 拉数据，不是设计器。
export default async function DeclaredListPage({
  params,
}: {
  params: Promise<{ flowKey: string }>;
}) {
  const { flowKey } = await params;
  const { language, phrases } = await currentLanguage();
  const flowRead = await readPlatform<PageFlowDocument>(`pages/${encodeURIComponent(flowKey)}`);
  if (flowRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!flowRead.body?.list?.apiPath || !flowRead.body.detail?.idField) {
    return <LoadFailedNotice phrases={phrases} status={flowRead.status || 404} />;
  }
  const flow = flowRead.body;
  const listPath = platformPathFromApi(flow.list!.apiPath!);
  const listRead = await readPlatform<unknown>(listPath);
  if (listRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (listRead.status >= 400) return <LoadFailedNotice phrases={phrases} status={listRead.status} />;
  const rows = itemsFromBody(listRead.body, flow.list?.itemsKey);
  const idField = flow.detail!.idField!;
  const title = language === "zh" ? flow.titleZh : flow.titleEn;
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
      {rows.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
          {rows.map((row, index) => {
            const id = String(row[idField] ?? index);
            const summary =
              typeof row.resultSummary === "string"
                ? row.resultSummary
                : typeof row.valuesJson === "string"
                  ? row.valuesJson
                  : id;
            return (
              <li key={id} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                <div className="min-w-0">
                  <p className="truncate font-mono text-xs text-muted">{id}</p>
                  <p className="truncate">{summary}</p>
                </div>
                <Link
                  href={`/pages/${encodeURIComponent(flowKey)}/${encodeURIComponent(id)}`}
                  className="shrink-0 rounded-md border border-border px-3 py-1.5 hover:bg-background"
                >
                  {phrases.openDetailAction}
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
