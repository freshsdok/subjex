"use client";

import type { KeyboardEvent, ReactNode } from "react";
import { useId, useState } from "react";
import { resolveActiveTabId } from "@/lib/tabs";

export type TabItem = {
  id: string;
  label: string;
  content: ReactNode;
};

// Tabs — 标签页积木：显式 tabs 数组；可选受控 activeId。
export function Tabs({
  tabs,
  activeId,
  onChange,
  emptyNote,
  ariaLabel,
}: {
  tabs: TabItem[];
  activeId?: string;
  onChange?: (id: string) => void;
  emptyNote?: string;
  ariaLabel?: string;
}) {
  const autoId = useId();
  const [uncontrolledId, setUncontrolledId] = useState<string | null>(() =>
    resolveActiveTabId(tabs),
  );
  const isControlled = activeId !== undefined;
  const selectedId = resolveActiveTabId(tabs, isControlled ? activeId : uncontrolledId);

  if (tabs.length === 0) {
    return <p className="text-sm text-muted">{emptyNote ?? "No tabs."}</p>;
  }

  function selectTab(id: string) {
    if (!isControlled) setUncontrolledId(id);
    onChange?.(id);
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight" && event.key !== "Home" && event.key !== "End") {
      return;
    }
    event.preventDefault();
    let next = index;
    if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    const target = tabs[next]!;
    selectTab(target.id);
    const el = document.getElementById(`${autoId}-tab-${target.id}`);
    el?.focus();
  }

  const active = tabs.find((tab) => tab.id === selectedId) ?? tabs[0]!;

  return (
    <div className="space-y-3">
      <div
        role="tablist"
        aria-label={ariaLabel}
        className="flex flex-wrap gap-1 border-b border-border text-sm"
      >
        {tabs.map((tab, index) => {
          const selected = tab.id === active.id;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={`${autoId}-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`${autoId}-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              data-active={selected ? "true" : "false"}
              className={
                selected
                  ? "-mb-px border-b-2 border-accent px-3 py-1.5 font-medium text-foreground"
                  : "px-3 py-1.5 text-muted hover:text-foreground"
              }
              onClick={() => selectTab(tab.id)}
              onKeyDown={(event) => onTabKeyDown(event, index)}
            >
              {tab.label}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={`${autoId}-panel-${active.id}`}
        aria-labelledby={`${autoId}-tab-${active.id}`}
      >
        {active.content}
      </div>
    </div>
  );
}
