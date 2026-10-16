/**
 * Simple layered org-chart layout for read-only SVG canvas (ORG-UI-5).
 * 只读组织图画布分层布局（ORG-UI-5）。
 */

import type { OrgTreeNode } from "@/lib/org-tree";

export type OrgCanvasBox = {
  organizationId: string;
  organizationName: string;
  organizationState: string;
  depth: number;
  x: number;
  y: number;
  width: number;
  height: number;
};

export type OrgCanvasEdge = {
  fromId: string;
  toId: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
};

export type OrgCanvasLayout = {
  boxes: OrgCanvasBox[];
  edges: OrgCanvasEdge[];
  width: number;
  height: number;
};

export type OrgCanvasLayoutOptions = {
  nodeWidth?: number;
  nodeHeight?: number;
  hGap?: number;
  vGap?: number;
  padding?: number;
};

/**
 * Layout forest top-down; siblings left-to-right in name/id order already on nodes.
 * 自上而下布局森林；兄弟顺序沿用节点已有排序。
 */
export function layoutOrgCanvas(
  forest: OrgTreeNode[],
  options: OrgCanvasLayoutOptions = {},
): OrgCanvasLayout {
  const nodeWidth = options.nodeWidth ?? 140;
  const nodeHeight = options.nodeHeight ?? 44;
  const hGap = options.hGap ?? 24;
  const vGap = options.vGap ?? 36;
  const padding = options.padding ?? 24;

  if (forest.length === 0) {
    return { boxes: [], edges: [], width: padding * 2, height: padding * 2 };
  }

  type Laid = { node: OrgTreeNode; depth: number; indexInDepth: number };
  const byDepth = new Map<number, Laid[]>();
  const walk = (nodes: OrgTreeNode[], depth: number) => {
    for (const node of nodes) {
      const list = byDepth.get(depth) ?? [];
      list.push({ node, depth, indexInDepth: list.length });
      byDepth.set(depth, list);
      if (node.children.length > 0) walk(node.children, depth + 1);
    }
  };
  walk(forest, 0);

  const maxDepth = Math.max(...byDepth.keys());
  let maxRowCount = 0;
  for (const row of byDepth.values()) {
    maxRowCount = Math.max(maxRowCount, row.length);
  }

  const boxes: OrgCanvasBox[] = [];
  const position = new Map<string, { x: number; y: number; cx: number; cy: number }>();

  for (let depth = 0; depth <= maxDepth; depth++) {
    const row = byDepth.get(depth) ?? [];
    const rowWidth = row.length * nodeWidth + Math.max(0, row.length - 1) * hGap;
    const startX = padding + Math.max(0, (maxRowCount * nodeWidth + Math.max(0, maxRowCount - 1) * hGap - rowWidth) / 2);
    row.forEach((laid, i) => {
      const x = startX + i * (nodeWidth + hGap);
      const y = padding + depth * (nodeHeight + vGap);
      boxes.push({
        organizationId: laid.node.organizationId,
        organizationName: laid.node.organizationName,
        organizationState: laid.node.organizationState,
        depth,
        x,
        y,
        width: nodeWidth,
        height: nodeHeight,
      });
      position.set(laid.node.organizationId, {
        x,
        y,
        cx: x + nodeWidth / 2,
        cy: y + nodeHeight / 2,
      });
    });
  }

  const edges: OrgCanvasEdge[] = [];
  const edgeWalk = (node: OrgTreeNode) => {
    const parentPos = position.get(node.organizationId);
    for (const child of node.children) {
      const childPos = position.get(child.organizationId);
      if (parentPos && childPos) {
        edges.push({
          fromId: node.organizationId,
          toId: child.organizationId,
          x1: parentPos.cx,
          y1: parentPos.y + nodeHeight,
          x2: childPos.cx,
          y2: childPos.y,
        });
      }
      edgeWalk(child);
    }
  };
  for (const root of forest) edgeWalk(root);

  const width =
    padding * 2 + maxRowCount * nodeWidth + Math.max(0, maxRowCount - 1) * hGap;
  const height = padding * 2 + (maxDepth + 1) * nodeHeight + maxDepth * vGap;

  return { boxes, edges, width, height };
}
