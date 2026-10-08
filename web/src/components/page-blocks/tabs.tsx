import type { ReactNode } from "react";

// Tabs — 标签页积木桩：暂渲染子节点或预留提示。
export function Tabs({
  reservedNote,
  children,
}: {
  reservedNote?: string;
  children?: ReactNode;
}) {
  if (children) return <div className="space-y-2">{children}</div>;
  return <p className="text-sm text-muted">{reservedNote ?? "Tabs (reserved)"}</p>;
}
