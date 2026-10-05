// Page/flow helpers — 页面/流程辅助：把声明里的 /api/v1/... 转成控制台代理路径，并从列表响应里取出行。

/** Strip /api/v1 prefix for readPlatform / proxy — 去掉 /api/v1 前缀，供 readPlatform 与代理使用。 */
export function platformPathFromApi(apiPath: string): string {
  const prefix = "/api/v1/";
  if (!apiPath.startsWith(prefix)) {
    throw new Error(`apiPath must start with ${prefix}`);
  }
  return apiPath.slice(prefix.length);
}

/**
 * Pull the row array from a list/detail API body.
 * 从列表/详情 API 响应里取出行数组；有 itemsKey 就取该字段，否则整份必须是数组。
 */
export function itemsFromBody(body: unknown, itemsKey: string | null | undefined): Record<string, unknown>[] {
  if (itemsKey) {
    if (!body || typeof body !== "object") return [];
    const value = (body as Record<string, unknown>)[itemsKey];
    return Array.isArray(value)
      ? value.filter((row): row is Record<string, unknown> => Boolean(row) && typeof row === "object")
      : [];
  }
  return Array.isArray(body)
    ? body.filter((row): row is Record<string, unknown> => Boolean(row) && typeof row === "object")
    : [];
}
