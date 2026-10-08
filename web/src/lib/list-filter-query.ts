// List filter/sort query helpers — 列表筛选/排序查询：识别通用 records 路径、解析 URL、拼 query。

/** True when apiPath is a generic entity records collection (supports sort/filter). */
export function isGenericEntityRecordsPath(apiPath: string): boolean {
  // Match /api/v1/entities/{key}/records or entities/{key}/records (platform path).
  return /(?:^|\/)entities\/[^/]+\/records\/?$/.test(apiPath.trim());
}

export type ListFilterParams = {
  sort?: string;
  order?: "asc" | "desc";
  filterField?: string;
  filterValue?: string;
};

type SearchParamBag = string | string[] | undefined;

function firstString(value: SearchParamBag): string {
  if (Array.isArray(value)) return value[0]?.trim() ?? "";
  return typeof value === "string" ? value.trim() : "";
}

/**
 * Parse list filter/sort from Next.js searchParams or a plain object.
 * Incomplete filter (field xor value) drops both. Invalid order is omitted.
 */
export function parseListFilterSearchParams(
  searchParams: Record<string, SearchParamBag>,
): ListFilterParams {
  const sort = firstString(searchParams.sort);
  const orderRaw = firstString(searchParams.order).toLowerCase();
  const filterField = firstString(searchParams.filterField);
  const filterValue = firstString(searchParams.filterValue);

  const result: ListFilterParams = {};
  if (sort) result.sort = sort;
  if (orderRaw === "asc" || orderRaw === "desc") result.order = orderRaw;
  if (filterField && filterValue) {
    result.filterField = filterField;
    result.filterValue = filterValue;
  }
  return result;
}

/** Build `?sort=…&order=…&filterField=…&filterValue=…` or empty string. */
export function buildListFilterQueryString(params: ListFilterParams): string {
  const cleaned = parseListFilterSearchParams({
    sort: params.sort,
    order: params.order,
    filterField: params.filterField,
    filterValue: params.filterValue,
  });
  const parts: string[] = [];
  if (cleaned.sort) parts.push(`sort=${encodeURIComponent(cleaned.sort)}`);
  if (cleaned.order) parts.push(`order=${encodeURIComponent(cleaned.order)}`);
  if (cleaned.filterField && cleaned.filterValue) {
    parts.push(`filterField=${encodeURIComponent(cleaned.filterField)}`);
    parts.push(`filterValue=${encodeURIComponent(cleaned.filterValue)}`);
  }
  return parts.length ? `?${parts.join("&")}` : "";
}

/** Append cleaned query to a platform path (no leading ? on path). */
export function platformPathWithListFilter(platformPath: string, params: ListFilterParams): string {
  const base = platformPath.split("?")[0] ?? platformPath;
  return `${base}${buildListFilterQueryString(params)}`;
}

/**
 * Field options for filter/sort selects: declared columns, else first row keys,
 * else active sort/filterField so an empty filtered list does not strand the bar.
 */
export function listFilterFieldOptions(
  columns: string[] | undefined,
  rows: Record<string, unknown>[],
  active: ListFilterParams,
): string[] {
  if (columns && columns.length > 0) return [...columns];
  const first = rows[0];
  if (first && typeof first === "object") {
    const keys = Object.keys(first);
    if (keys.length > 0) return keys;
  }
  const fallback: string[] = [];
  if (active.filterField) fallback.push(active.filterField);
  if (active.sort && !fallback.includes(active.sort)) fallback.push(active.sort);
  return fallback;
}

export function listFilterHasActive(params: ListFilterParams): boolean {
  return Boolean(params.sort || params.order || (params.filterField && params.filterValue));
}
