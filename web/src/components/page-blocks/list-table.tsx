import Link from "next/link";
import { formatCellValue, rowSummary, type ListRow } from "@/lib/list-table";

type ListTablePhrases = {
  emptyList: string;
  openDetailAction: string;
};

// ListTable — 列表表积木：渲染声明列表行，并按 id 链到详情。
export function ListTable({
  rows,
  idField,
  columns,
  detailHref,
  phrases,
}: {
  rows: ListRow[];
  idField: string;
  columns?: string[];
  detailHref: (id: string) => string;
  phrases: ListTablePhrases;
}) {
  if (rows.length === 0) {
    return <p className="text-sm text-muted">{phrases.emptyList}</p>;
  }
  const showColumns = columns && columns.length > 0;
  return (
    <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
      {rows.map((row, index) => {
        const id = String(row[idField] ?? index);
        return (
          <li key={id} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
            <div className="min-w-0">
              <p className="truncate font-mono text-xs text-muted">{id}</p>
              {showColumns ? (
                <dl className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  {columns!.map((column) => (
                    <div key={column} className="min-w-0">
                      <dt className="inline font-mono text-muted">{column}: </dt>
                      <dd className="inline truncate">{formatCellValue(row[column])}</dd>
                    </div>
                  ))}
                </dl>
              ) : (
                <p className="truncate">{rowSummary(row, id)}</p>
              )}
            </div>
            <Link
              href={detailHref(id)}
              className="shrink-0 rounded-md border border-border px-3 py-1.5 hover:bg-background"
            >
              {phrases.openDetailAction}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
