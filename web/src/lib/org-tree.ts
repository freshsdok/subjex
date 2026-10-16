/**
 * Org tree helpers (ORG-UI-1) — build indented forest from Organization rows
 * using parentOrganizationId (CONTAINS). Search/locate expands ancestors.
 * Zero model change; client-side only.
 * <p>
 * 组织树辅助：用 parentOrganizationId 建森林；搜索定位展开祖先；仅前端。
 */

export type OrgTreeRow = {
  organizationId?: string;
  parentOrganizationId?: string | null;
  organizationName?: string;
  organizationState?: string;
};

export type OrgTreeNode = {
  organizationId: string;
  organizationName: string;
  organizationState: string;
  parentOrganizationId: string | null;
  children: OrgTreeNode[];
};

function rowId(row: OrgTreeRow): string | null {
  const id = (row.organizationId ?? "").trim();
  return id || null;
}

function parentId(row: OrgTreeRow): string | null {
  const p = (row.parentOrganizationId ?? "").trim();
  return p || null;
}

/**
 * Build a forest from flat org rows. Missing/unknown parents become roots.
 * Cycles: first visit wins; later back-edge ignored (child stays where first attached).
 * Sort siblings by name then id (stable UX until order field exists).
 */
export function buildOrgForest(rows: OrgTreeRow[]): OrgTreeNode[] {
  const byId = new Map<string, OrgTreeNode>();
  for (const row of rows) {
    const id = rowId(row);
    if (!id || byId.has(id)) continue;
    byId.set(id, {
      organizationId: id,
      organizationName: (row.organizationName ?? "").trim() || id,
      organizationState: (row.organizationState ?? "").trim() || "ACTIVE",
      parentOrganizationId: parentId(row),
      children: [],
    });
  }

  const roots: OrgTreeNode[] = [];
  const attached = new Set<string>();

  for (const node of byId.values()) {
    const p = node.parentOrganizationId;
    if (p && byId.has(p) && p !== node.organizationId) {
      // defer attach in second pass to detect simple cycles via path
      continue;
    }
    roots.push(node);
    attached.add(node.organizationId);
  }

  // Attach children; if parent would create a cycle (ancestor chain), treat as root.
  for (const node of byId.values()) {
    if (attached.has(node.organizationId)) continue;
    const p = node.parentOrganizationId;
    if (!p || !byId.has(p)) {
      roots.push(node);
      attached.add(node.organizationId);
      continue;
    }
    if (wouldCreateCycle(byId, p, node.organizationId)) {
      node.parentOrganizationId = null;
      roots.push(node);
      attached.add(node.organizationId);
      continue;
    }
    byId.get(p)!.children.push(node);
    attached.add(node.organizationId);
  }

  const sortRec = (nodes: OrgTreeNode[]) => {
    nodes.sort((a, b) => {
      const byName = a.organizationName.localeCompare(b.organizationName, undefined, {
        sensitivity: "base",
      });
      if (byName !== 0) return byName;
      return a.organizationId.localeCompare(b.organizationId);
    });
    for (const n of nodes) sortRec(n.children);
  };
  sortRec(roots);
  return roots;
}

function wouldCreateCycle(
  byId: Map<string, OrgTreeNode>,
  parentIdValue: string,
  childId: string,
): boolean {
  let walk: string | null = parentIdValue;
  const seen = new Set<string>();
  while (walk) {
    if (walk === childId) return true;
    if (seen.has(walk)) return true;
    seen.add(walk);
    walk = byId.get(walk)?.parentOrganizationId ?? null;
  }
  return false;
}

/** Flatten forest depth-first — 深度优先展平。 */
export function flattenOrgForest(forest: OrgTreeNode[]): OrgTreeNode[] {
  const out: OrgTreeNode[] = [];
  const walk = (nodes: OrgTreeNode[]) => {
    for (const n of nodes) {
      out.push(n);
      walk(n.children);
    }
  };
  walk(forest);
  return out;
}

/** Ancestor ids from root to parent of target (exclusive of target). */
export function ancestorIdsOf(forest: OrgTreeNode[], organizationId: string): string[] {
  const path: string[] = [];
  const find = (nodes: OrgTreeNode[], trail: string[]): boolean => {
    for (const n of nodes) {
      if (n.organizationId === organizationId) {
        path.push(...trail);
        return true;
      }
      if (find(n.children, [...trail, n.organizationId])) return true;
    }
    return false;
  };
  find(forest, []);
  return path;
}

function matchesQuery(node: OrgTreeNode, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return false;
  return (
    node.organizationId.toLowerCase().includes(q) ||
    node.organizationName.toLowerCase().includes(q)
  );
}

/** Ids that match the search query (case-insensitive id/name substring). */
export function searchMatchingIds(forest: OrgTreeNode[], query: string): Set<string> {
  const hits = new Set<string>();
  if (!query.trim()) return hits;
  for (const n of flattenOrgForest(forest)) {
    if (matchesQuery(n, query)) hits.add(n.organizationId);
  }
  return hits;
}

/**
 * Expand set for search: all ancestors of each hit so the match is visible.
 * Empty query → empty set (caller decides default expand).
 */
export function expandIdsForSearch(forest: OrgTreeNode[], query: string): Set<string> {
  const expand = new Set<string>();
  const hits = searchMatchingIds(forest, query);
  for (const id of hits) {
    for (const a of ancestorIdsOf(forest, id)) expand.add(a);
  }
  return expand;
}

/** Default expanded: all non-leaf nodes (small tenants) — 默认展开全部有子节点的。 */
export function defaultExpandedIds(forest: OrgTreeNode[]): Set<string> {
  const out = new Set<string>();
  for (const n of flattenOrgForest(forest)) {
    if (n.children.length > 0) out.add(n.organizationId);
  }
  return out;
}

export function findOrgNode(forest: OrgTreeNode[], organizationId: string): OrgTreeNode | null {
  for (const n of flattenOrgForest(forest)) {
    if (n.organizationId === organizationId) return n;
  }
  return null;
}

/**
 * True if {@code candidateId} is {@code organizationId} or in its CONTAINS subtree.
 * 若 candidate 是自身或其 CONTAINS 子树中的节点则为 true（客户端防环）。
 */
export function isSelfOrDescendant(
  forest: OrgTreeNode[],
  organizationId: string,
  candidateId: string,
): boolean {
  if (organizationId === candidateId) return true;
  const node = findOrgNode(forest, organizationId);
  if (!node) return false;
  const walk = (n: OrgTreeNode): boolean => {
    for (const c of n.children) {
      if (c.organizationId === candidateId) return true;
      if (walk(c)) return true;
    }
    return false;
  };
  return walk(node);
}

/** Client mirror of server cycle check: new parent cannot be self/descendant of moved node. */
export function wouldClientMoveCycle(
  forest: OrgTreeNode[],
  movedId: string,
  newParentId: string | null,
): boolean {
  if (newParentId == null || newParentId === "") return false;
  return isSelfOrDescendant(forest, movedId, newParentId);
}

export type MovePreviewPayload = {
  organizationId?: string;
  fromParentOrganizationId?: string | null;
  toParentOrganizationId?: string | null;
  subtreeOrganizationCount?: number;
  directMembershipCount?: number;
  wouldCycle?: boolean;
  inScope?: boolean;
};
