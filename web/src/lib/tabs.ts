// Tabs helpers — 标签页积木辅助（默认选中 id，供组件与单测共用）。

export type TabSpec = {
  id: string;
  label: string;
};

/**
 * Resolve initial/active tab id: prefer requested when present in tabs,
 * else first tab id, else null when empty.
 * 解析默认/当前标签：优先请求的 id（须在列表中），否则第一项，空列表为 null。
 */
export function resolveActiveTabId(
  tabs: readonly TabSpec[],
  requestedId?: string | null,
): string | null {
  if (tabs.length === 0) return null;
  if (requestedId != null && requestedId !== "") {
    const match = tabs.find((tab) => tab.id === requestedId);
    if (match) return match.id;
  }
  return tabs[0]!.id;
}
