"use client";

import { useEffect, useMemo, useRef } from "react";
import type { PhraseBook } from "@/i18n/phrases";
import { layoutOrgCanvas } from "@/lib/org-canvas-layout";
import type { OrgTreeNode } from "@/lib/org-tree";
import { isOrgDisabledState } from "@/lib/org-console";

type Props = {
  phrases: PhraseBook;
  forest: OrgTreeNode[];
  selectedId: string | null;
  onSelect: (organizationId: string) => void;
};

/**
 * Read-only SVG org chart (ORG-UI-5) — pan/zoom via scroll container; click selects.
 * 只读 SVG 组织图；滚动平移缩放容器；点击选中。
 */
export function OrgCanvas({ phrases, forest, selectedId, onSelect }: Props) {
  const layout = useMemo(() => layoutOrgCanvas(forest), [forest]);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!selectedId || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-canvas-org="${CSS.escape(selectedId)}"]`);
    el?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
  }, [selectedId]);

  if (forest.length === 0) {
    return <p className="text-sm text-muted">{phrases.emptyList}</p>;
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted">{phrases.orgCanvasHint}</p>
      <div
        ref={containerRef}
        className="max-h-[28rem] overflow-auto rounded-md border border-border bg-background"
        aria-label={phrases.orgCanvasTitle}
      >
        <svg
          width={layout.width}
          height={layout.height}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          role="img"
          className="block min-w-full"
        >
          <title>{phrases.orgCanvasTitle}</title>
          {layout.edges.map((edge) => (
            <line
              key={`${edge.fromId}->${edge.toId}`}
              x1={edge.x1}
              y1={edge.y1}
              x2={edge.x2}
              y2={edge.y2}
              stroke="currentColor"
              strokeOpacity={0.35}
              strokeWidth={1.5}
            />
          ))}
          {layout.boxes.map((box) => {
            const selected = selectedId === box.organizationId;
            const disabled = isOrgDisabledState(box.organizationState);
            return (
              <g
                key={box.organizationId}
                data-canvas-org={box.organizationId}
                transform={`translate(${box.x}, ${box.y})`}
                opacity={disabled ? 0.55 : 1}
                style={{ cursor: "pointer" }}
                onClick={() => onSelect(box.organizationId)}
              >
                <rect
                  width={box.width}
                  height={box.height}
                  rx={6}
                  ry={6}
                  fill={selected ? "var(--foreground)" : "var(--surface, #fff)"}
                  stroke="currentColor"
                  strokeOpacity={selected ? 1 : 0.35}
                  strokeWidth={selected ? 2 : 1}
                />
                <text
                  x={box.width / 2}
                  y={box.height / 2 - 4}
                  textAnchor="middle"
                  fontSize={11}
                  fill={selected ? "var(--background, #fff)" : "currentColor"}
                >
                  {truncate(box.organizationName, 16)}
                </text>
                <text
                  x={box.width / 2}
                  y={box.height / 2 + 10}
                  textAnchor="middle"
                  fontSize={9}
                  fill={selected ? "var(--background, #fff)" : "currentColor"}
                  opacity={0.7}
                  fontFamily="ui-monospace, monospace"
                >
                  {truncate(box.organizationId, 18)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}

function truncate(value: string, max: number): string {
  if (value.length <= max) return value;
  return `${value.slice(0, max - 1)}…`;
}
