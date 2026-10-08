import type { ReactNode } from "react";

// FlowSorter — 流程分拣器桩：预留分支路由位。
export function FlowSorter({
  reservedNote,
  children,
}: {
  reservedNote?: string;
  children?: ReactNode;
}) {
  if (children) return <div className="space-y-2">{children}</div>;
  return <p className="text-sm text-muted">{reservedNote ?? "FlowSorter (reserved)"}</p>;
}
