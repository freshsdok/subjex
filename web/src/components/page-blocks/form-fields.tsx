import type { ReactNode } from "react";

// FormFields — 实体驱动字段生成桩（Z2 暂占位；提交页仍用表单 YAML）。
export function FormFields({
  reservedNote,
  children,
}: {
  reservedNote?: string;
  children?: ReactNode;
}) {
  if (children) return <div className="flex flex-col gap-3">{children}</div>;
  return <p className="text-sm text-muted">{reservedNote ?? "FormFields (reserved)"}</p>;
}
