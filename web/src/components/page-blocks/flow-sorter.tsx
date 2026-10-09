import Link from "next/link";
import type { FlowSorterOption } from "@/lib/flow-sorter";

// FlowSorter — 流程分拣器：把声明里的列表/新建等分支渲染成链接行。
export function FlowSorter({
  options,
  ariaLabel,
}: {
  options: FlowSorterOption[];
  ariaLabel?: string;
}) {
  if (options.length === 0) return null;

  return (
    <nav
      aria-label={ariaLabel ?? "Flow sorter"}
      className="mb-4 flex flex-wrap gap-2 text-sm"
    >
      {options.map((option) => {
        const className =
          option.key === "new"
            ? "rounded-md bg-accent px-3 py-1.5 text-white"
            : "rounded-md border border-border px-3 py-1.5";
        if (option.href.startsWith("/")) {
          return (
            <Link key={option.key} href={option.href} className={className}>
              {option.label}
            </Link>
          );
        }
        return (
          <a key={option.key} href={option.href} className={className}>
            {option.label}
          </a>
        );
      })}
    </nav>
  );
}
