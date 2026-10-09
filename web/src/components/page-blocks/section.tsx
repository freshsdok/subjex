import type { ReactNode } from "react";

// Section — 分区积木：浅色卡片式包裹，可选标题与提示。
export function Section({
  title,
  hint,
  className,
  children,
}: {
  title?: string;
  hint?: string;
  className?: string;
  children?: ReactNode;
}) {
  const rootClass = [
    "space-y-3 rounded-lg border border-border bg-surface p-4",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <section className={rootClass}>
      {title || hint ? (
        <header className="space-y-1">
          {title ? <h3 className="text-sm font-medium">{title}</h3> : null}
          {hint ? <p className="text-xs text-muted">{hint}</p> : null}
        </header>
      ) : null}
      {children}
    </section>
  );
}
