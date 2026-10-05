import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type PagesIndexDocument = {
  pages?: {
    flowKey?: string;
    titleZh?: string;
    titleEn?: string;
    permission?: string;
    tenantScoped?: boolean;
  }[];
};

// Pages index — 页面目录：按检入的流程声明列出列表入口；标明权限，无权限则不给打开链接。
export default async function PagesIndexPage() {
  const { language, phrases } = await currentLanguage();
  const [indexRead, meRead] = await Promise.all([
    readPlatform<PagesIndexDocument>("pages"),
    readPlatform<OperatorSelfDocument>("me"),
  ]);
  if (indexRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!indexRead.body?.pages) {
    return <LoadFailedNotice phrases={phrases} status={indexRead.status || 500} />;
  }
  const myPermissions = new Set(meRead.body?.permissions ?? []);
  const pages = indexRead.body.pages.filter(
    (entry): entry is {
      flowKey: string;
      titleZh: string;
      titleEn: string;
      permission?: string;
      tenantScoped?: boolean;
    } => Boolean(entry.flowKey && entry.titleZh && entry.titleEn),
  );
  return (
    <section>
      <PageHeading title={phrases.pagesTitle} hint={phrases.pagesHint} />
      {pages.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
          {pages.map((entry) => {
            const title = language === "zh" ? entry.titleZh : entry.titleEn;
            const allowed = Boolean(entry.permission && myPermissions.has(entry.permission));
            return (
              <li key={entry.flowKey} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                <div>
                  <p className="font-medium">{title}</p>
                  <p className="text-xs text-muted">
                    {phrases.flowKeyLabel}: <code>{entry.flowKey}</code>
                    {entry.permission ? (
                      <>
                        {" · "}
                        <code>{entry.permission}</code>
                      </>
                    ) : null}
                    {entry.tenantScoped ? " · tenant" : null}
                  </p>
                </div>
                {allowed ? (
                  <Link
                    href={`/pages/${encodeURIComponent(entry.flowKey)}`}
                    className="rounded-md border border-border px-3 py-1.5 hover:bg-background"
                  >
                    {phrases.openListAction}
                  </Link>
                ) : (
                  <span className="rounded-md border border-border px-3 py-1.5 text-muted opacity-60">
                    {phrases.openListAction}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
