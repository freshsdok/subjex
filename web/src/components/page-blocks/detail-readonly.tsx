import { formatCellValue } from "@/lib/list-table";

// DetailReadonly — 只读详情积木：键值展示一条记录。
export function DetailReadonly({ record }: { record: Record<string, unknown> }) {
  return (
    <dl className="max-w-2xl space-y-2 rounded-lg border border-border bg-surface p-4 text-sm">
      {Object.entries(record).map(([key, value]) => (
        <div key={key} className="grid grid-cols-[10rem_1fr] gap-2">
          <dt className="font-mono text-xs text-muted">{key}</dt>
          <dd className="break-all font-mono text-xs">{formatCellValue(value)}</dd>
        </div>
      ))}
    </dl>
  );
}
