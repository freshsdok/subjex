import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { itemsFromBody, platformPathFromApi } from "@/lib/page-flow";
import { readPlatform } from "@/server/platform-reader";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  detail?: { apiPath?: string; itemsKey?: string | null; idField?: string };
};

// Declared detail page — 声明式详情页：从声明的 apiPath 取行，再按 idField 挑出一项。
export default async function DeclaredDetailPage({
  params,
}: {
  params: Promise<{ flowKey: string; id: string }>;
}) {
  const { flowKey, id } = await params;
  const { language, phrases } = await currentLanguage();
  const flowRead = await readPlatform<PageFlowDocument>(`pages/${encodeURIComponent(flowKey)}`);
  if (flowRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!flowRead.body?.detail?.apiPath || !flowRead.body.detail.idField) {
    return <LoadFailedNotice phrases={phrases} status={flowRead.status || 404} />;
  }
  const detail = flowRead.body.detail;
  const listRead = await readPlatform<unknown>(platformPathFromApi(detail.apiPath!));
  if (listRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (listRead.status >= 400) return <LoadFailedNotice phrases={phrases} status={listRead.status} />;
  const rows = itemsFromBody(listRead.body, detail.itemsKey);
  const row = rows.find((entry) => String(entry[detail.idField!]) === id);
  if (!row) return <LoadFailedNotice phrases={phrases} status={404} />;
  const title = language === "zh" ? flowRead.body.titleZh : flowRead.body.titleEn;
  return (
    <section>
      <PageHeading title={title ?? flowKey} hint={phrases.pagesDetailHint} />
      <p className="mb-4 text-sm">
        <Link href={`/pages/${encodeURIComponent(flowKey)}`} className="text-accent underline">
          {phrases.backToListAction}
        </Link>
      </p>
      <dl className="max-w-2xl space-y-2 rounded-lg border border-border bg-surface p-4 text-sm">
        {Object.entries(row).map(([key, value]) => (
          <div key={key} className="grid grid-cols-[10rem_1fr] gap-2">
            <dt className="font-mono text-xs text-muted">{key}</dt>
            <dd className="break-all font-mono text-xs">{formatValue(value)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function formatValue(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
