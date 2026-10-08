import Link from "next/link";
import type { ListFilterParams } from "@/lib/list-filter-query";

type ListFilterBarPhrases = {
  listFilterFieldLabel: string;
  listFilterValueLabel: string;
  listSortFieldLabel: string;
  listSortOrderLabel: string;
  listSortOrderAsc: string;
  listSortOrderDesc: string;
  listFilterApplyAction: string;
  listFilterClearAction: string;
  listFilterAnyField: string;
};

// ListFilterBar — 列表筛选/排序条：GET 表单写入 URL searchParams，再由服务端拉平台。
export function ListFilterBar({
  actionPath,
  fields,
  active,
  phrases,
}: {
  actionPath: string;
  fields: string[];
  active: ListFilterParams;
  phrases: ListFilterBarPhrases;
}) {
  if (fields.length === 0) return null;
  const clearHref = actionPath;
  return (
    <form
      method="get"
      action={actionPath}
      className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
    >
      <label className="flex min-w-[8rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.listFilterFieldLabel}</span>
        <select
          name="filterField"
          defaultValue={active.filterField ?? ""}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="">{phrases.listFilterAnyField}</option>
          {fields.map((field) => (
            <option key={field} value={field}>
              {field}
            </option>
          ))}
        </select>
      </label>
      <label className="flex min-w-[10rem] flex-1 flex-col gap-1">
        <span className="text-xs text-muted">{phrases.listFilterValueLabel}</span>
        <input
          name="filterValue"
          type="text"
          defaultValue={active.filterValue ?? ""}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[8rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.listSortFieldLabel}</span>
        <select
          name="sort"
          defaultValue={active.sort ?? ""}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="">{phrases.listFilterAnyField}</option>
          {fields.map((field) => (
            <option key={field} value={field}>
              {field}
            </option>
          ))}
        </select>
      </label>
      <label className="flex min-w-[7rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.listSortOrderLabel}</span>
        <select
          name="order"
          defaultValue={active.order ?? "asc"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="asc">{phrases.listSortOrderAsc}</option>
          <option value="desc">{phrases.listSortOrderDesc}</option>
        </select>
      </label>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.listFilterApplyAction}
        </button>
        <Link href={clearHref} className="rounded-md border border-border px-3 py-1.5 hover:bg-background">
          {phrases.listFilterClearAction}
        </Link>
      </div>
    </form>
  );
}
