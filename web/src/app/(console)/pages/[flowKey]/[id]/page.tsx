import Link from "next/link";
import { DetailReadonly } from "@/components/page-blocks";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  isGenericRecordsCollectionPath,
  itemsFromBody,
  platformPathFromApi,
  recordDetailPlatformPath,
} from "@/lib/page-flow";
import { readPlatform } from "@/server/platform-reader";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  detail?: { apiPath?: string; itemsKey?: string | null; idField?: string };
};

// Declared detail page — 声明式详情页：优先 GET /records/{id}，否则列表里按 idField 挑一项。
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
  let row: Record<string, unknown> | undefined;

  if (isGenericRecordsCollectionPath(detail.apiPath!)) {
    const oneRead = await readPlatform<Record<string, unknown>>(
      recordDetailPlatformPath(detail.apiPath!, id),
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
    const listRead = await readPlatform<unknown>(platformPathFromApi(detail.apiPath!));
    if (listRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
    if (listRead.status >= 400) return <LoadFailedNotice phrases={phrases} status={listRead.status} />;
    const rows = itemsFromBody(listRead.body, detail.itemsKey);
    row = rows.find((entry) => String(entry[detail.idField!]) === id);
  }

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
      <DetailReadonly record={row} />
    </section>
  );
}
