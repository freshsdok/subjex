import type { ReactNode } from "react";

// SubmitBar — 提交栏积木：包裹核对/确认/取消等操作按钮。
export function SubmitBar({ children }: { children: ReactNode }) {
  return <div className="mt-4 flex flex-wrap gap-2">{children}</div>;
}
