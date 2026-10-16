"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { type PhraseBook } from "@/i18n/phrases";
import { downloadOrgPng, downloadOrgSvg } from "@/lib/org-canvas-export";
import { layoutMatrixEdges, layoutOrgCanvas, type OrgMatrixLink } from "@/lib/org-canvas-layout";
import type { OrgTreeNode } from "@/lib/org-tree";
import { isOrgDisabledState } from "@/lib/org-console";

type Props = {
  phrases: PhraseBook;
  forest: OrgTreeNode[];
  selectedId: string | null;
  onSelect: (organizationId: string) => void;
  exportBasename?: string;
  /** Later L1 MATRIX_OF overlays — dashed; never blocks CONTAINS drag (tree only). */
  matrixLinks?: OrgMatrixLink[];
};

/**
 * Read-only SVG org chart (ORG-UI-5) + S3 SVG/PNG export + L1 MATRIX_OF dashed overlay.
 * 只读 SVG 组织图；S3 导出；L1 矩阵虚线叠加（不替代 CONTAINS）。
 */
export function OrgCanvas({
  phrases,
  forest,
  selectedId,
  onSelect,
  exportBasename,
  matrixLinks = [],
}: Props) {
  const layout = useMemo(() => layoutOrgCanvas(forest), [forest]);
  const matrixEdges = useMemo(
    () => layoutMatrixEdges(layout, matrixLinks),
    [layout, matrixLinks],
  );
  const containerRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [exportProblem, setExportProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedId || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-canvas-org="${CSS.escape(selectedId)}"]`);
    el?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
  }, [selectedId]);

  function baseName(): string {
    return (exportBasename || "org-chart").replace(/[^\w.-]+/g, "_");
  }

  function exportSvg() {
    setExportProblem(null);
    if (!svgRef.current) return;
    try {
      downloadOrgSvg(svgRef.current, `${baseName()}.svg`);
    } catch {
      setExportProblem(phrases.orgCanvasExportFailed);
    }
  }

  async function exportPng() {
    setExportProblem(null);
    if (!svgRef.current) return;
    try {
      await downloadOrgPng(svgRef.current, `${baseName()}.png`);
    } catch {
      setExportProblem(phrases.orgCanvasExportFailed);
    }
  }

  if (forest.length === 0) {
    return <p className="text-sm text-muted">{phrases.emptyList}</p>;
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted">{phrases.orgCanvasHint}</p>
        <div className="flex gap-1">
          <button
            type="button"
            onClick={exportSvg}
            className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          >
            {phrases.orgCanvasExportSvg}
          </button>
          <button
            type="button"
            onClick={() => void exportPng()}
            className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          >
            {phrases.orgCanvasExportPng}
          </button>
        </div>
      </div>
      {matrixLinks.length > 0 ? (
        <p className="text-xs text-muted">{phrases.orgMatrixCanvasLegend}</p>
      ) : null}
      {exportProblem ? (
        <p role="alert" className="text-xs text-red-700">
          {exportProblem}
        </p>
      ) : null}
      <div
        ref={containerRef}
        className="max-h-[28rem] overflow-auto rounded-md border border-border bg-background"
        aria-label={phrases.orgCanvasTitle}
      >
        <svg
          ref={svgRef}
          width={layout.width}
          height={layout.height}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          role="img"
          className="block min-w-full"
        >
          <title>{phrases.orgCanvasTitle}</title>
          {layout.edges.map((edge) => (
            <line
              key={`contains:${edge.fromId}->${edge.toId}`}
              x1={edge.x1}
              y1={edge.y1}
              x2={edge.x2}
              y2={edge.y2}
              stroke="currentColor"
              strokeOpacity={0.35}
              strokeWidth={1.5}
            />
          ))}
          {matrixEdges.map((edge) => (
            <line
              key={`matrix:${edge.fromId}->${edge.toId}`}
              x1={edge.x1}
              y1={edge.y1}
              x2={edge.x2}
              y2={edge.y2}
              stroke="currentColor"
              strokeOpacity={0.45}
              strokeWidth={1.25}
              strokeDasharray="4 3"
              data-matrix-edge={`${edge.fromId}->${edge.toId}`}
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
