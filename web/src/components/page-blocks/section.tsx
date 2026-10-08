import type { ReactNode } from "react";

// Section — 分区积木桩：简单包裹子节点。
export function Section({
  title,
  children,
}: {
  title?: string;
  children?: ReactNode;
}) {
  return (
    <section className="space-y-2">
      {title ? <h3 className="text-sm font-medium">{title}</h3> : null}
      {children}
    </section>
  );
}
