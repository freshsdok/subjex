"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { OrgCanvas } from "@/components/org-canvas";
import {
  ancestorIdsOf,
  buildOrgForest,
  defaultExpandedIds,
  expandIdsForSearch,
  findOrgNode,
  searchMatchingIds,
  wouldClientMoveCycle,
  type MovePreviewPayload,
  type OrgTreeNode,
  type OrgTreeRow,
} from "@/lib/org-tree";
import {
  ORG_MEMBERSHIP_STATE_ACTIVE,
  ORG_UNIT_STATE_ACTIVE,
  isOrgDisabledState,
  normalizeOrgId,
  toggledOrgState,
} from "@/lib/org-console";

type MembershipRow = {
  subjectId?: string;
  organizationId?: string;
  membershipState?: string;
};

type MoveReview = {
  movedId: string;
  newParentId: string | null;
  preview: MovePreviewPayload;
};

type FormStep = "editing" | "reviewing" | "saving";

type Props = {
  phrases: PhraseBook;
  organizations: OrgTreeRow[];
  memberships: MembershipRow[];
  tenantId: string;
  canWrite: boolean;
  onMoved?: () => void;
};

/**
 * Org tree + canvas + side panel (ORG-UI-1/2/4/5).
 * 组织树/画布/侧栏：拖拽改挂载、侧栏写、只读图。
 */
export function OrgTreePanel({
  phrases,
  organizations,
  memberships,
  tenantId,
  canWrite,
  onMoved,
}: Props) {
  const forest = useMemo(() => buildOrgForest(organizations), [organizations]);
  const [vizMode, setVizMode] = useState<"tree" | "canvas">("tree");
  const [query, setQuery] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(() => defaultExpandedIds(forest));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [dragOverId, setDragOverId] = useState<string | null>(null);
  const [rootDragOver, setRootDragOver] = useState(false);
  const [moveReview, setMoveReview] = useState<MoveReview | null>(null);
  const [moveStep, setMoveStep] = useState<"idle" | "reviewing" | "saving">("idle");
  const [moveProblem, setMoveProblem] = useState<string | null>(null);
  const [multiIds, setMultiIds] = useState<Set<string>>(new Set());
  const [batchTarget, setBatchTarget] = useState<string>("");
  const [batchStep, setBatchStep] = useState<"idle" | "reviewing" | "saving">("idle");
  const [batchProblem, setBatchProblem] = useState<string | null>(null);
  const [batchSummary, setBatchSummary] = useState<string | null>(null);

  const [childId, setChildId] = useState("");
  const [childName, setChildName] = useState("");
  const [childStep, setChildStep] = useState<FormStep>("editing");
  const [childProblem, setChildProblem] = useState<string | null>(null);

  const [toggleStep, setToggleStep] = useState<FormStep>("editing");
  const [toggleProblem, setToggleProblem] = useState<string | null>(null);

  const [memberSubject, setMemberSubject] = useState("");
  const [memberStep, setMemberStep] = useState<FormStep>("editing");
  const [memberProblem, setMemberProblem] = useState<string | null>(null);

  const [removeSubject, setRemoveSubject] = useState<string | null>(null);
  const [removeStep, setRemoveStep] = useState<FormStep>("editing");
  const [removeProblem, setRemoveProblem] = useState<string | null>(null);

  const [panelNotice, setPanelNotice] = useState<string | null>(null);

  const [mergeTarget, setMergeTarget] = useState("");
  const [mergeStep, setMergeStep] = useState<FormStep>("editing");
  const [mergeProblem, setMergeProblem] = useState<string | null>(null);
  const [mergeSummary, setMergeSummary] = useState<string | null>(null);

  const [splitId, setSplitId] = useState("");
  const [splitName, setSplitName] = useState("");
  const [splitChildren, setSplitChildren] = useState("");
  const [splitMembers, setSplitMembers] = useState("");
  const [splitStep, setSplitStep] = useState<FormStep>("editing");
  const [splitProblem, setSplitProblem] = useState<string | null>(null);
  const [splitSummary, setSplitSummary] = useState<string | null>(null);

  const [managerSubject, setManagerSubject] = useState("");
  const [managerStep, setManagerStep] = useState<FormStep>("editing");
  const [managerMode, setManagerMode] = useState<"assign" | "clear">("assign");
  const [managerProblem, setManagerProblem] = useState<string | null>(null);

  const listRef = useRef<HTMLDivElement>(null);
  const skipExpandSync = useRef(false);
  const prevForestKey = useRef("");

  const forestKey = useMemo(
    () =>
      organizations
        .map((o) => `${o.organizationId}:${o.parentOrganizationId}:${o.organizationState}`)
        .sort()
        .join("|"),
    [organizations],
  );

  useEffect(() => {
    const first = prevForestKey.current === "";
    prevForestKey.current = forestKey;
    setExpanded(defaultExpandedIds(forest));
    setMoveReview(null);
    setMoveStep("idle");
    setMoveProblem(null);
    setMultiIds(new Set());
    setBatchStep("idle");
    setBatchProblem(null);
    setBatchSummary(null);
    if (first) {
      setSelectedId(null);
      setQuery("");
      return;
    }
    setSelectedId((prev) => {
      if (prev && findOrgNode(forest, prev)) return prev;
      return null;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refresh keeps selection when possible
  }, [forestKey]);

  const hits = useMemo(() => searchMatchingIds(forest, query), [forest, query]);

  useEffect(() => {
    if (skipExpandSync.current) {
      skipExpandSync.current = false;
      return;
    }
    if (!query.trim()) return;
    setExpanded((prev) => {
      const next = new Set(prev);
      for (const id of expandIdsForSearch(forest, query)) next.add(id);
      return next;
    });
  }, [query, forest]);

  useEffect(() => {
    if (vizMode !== "tree" || !query.trim() || hits.size === 0) return;
    const first = [...hits][0];
    const el = listRef.current?.querySelector(`[data-org-id="${CSS.escape(first)}"]`);
    el?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [query, hits, vizMode]);

  function toggleExpand(id: string) {
    skipExpandSync.current = true;
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function selectNode(id: string) {
    setSelectedId(id);
    setChildStep("editing");
    setChildProblem(null);
    setToggleStep("editing");
    setToggleProblem(null);
    setMemberStep("editing");
    setMemberProblem(null);
    setRemoveSubject(null);
    setRemoveStep("editing");
    setPanelNotice(null);
    setExpanded((prev) => {
      const next = new Set(prev);
      for (const a of ancestorIdsOf(forest, id)) next.add(a);
      return next;
    });
    if (vizMode === "tree") {
      requestAnimationFrame(() => {
        const el = listRef.current?.querySelector(`[data-org-id="${CSS.escape(id)}"]`);
        el?.scrollIntoView({ block: "nearest", behavior: "smooth" });
      });
    }
  }

  function refresh() {
    onMoved?.();
  }

  async function moveSibling(direction: -1 | 1) {
    if (!canWrite || !selectedId) return;
    const node = findOrgNode(forest, selectedId);
    if (!node?.parentOrganizationId) return;
    const parent = findOrgNode(forest, node.parentOrganizationId);
    if (!parent) return;
    const siblings = parent.children.map((c) => c.organizationId);
    const idx = siblings.indexOf(selectedId);
    const swap = idx + direction;
    if (idx < 0 || swap < 0 || swap >= siblings.length) return;
    const ordered = [...siblings];
    const tmp = ordered[idx];
    ordered[idx] = ordered[swap];
    ordered[swap] = tmp;
    const tid = tenantId.trim();
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(node.parentOrganizationId)}/children/order?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ orderedChildIds: ordered }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setPanelNotice(null);
      setMoveProblem(fillPhrase(phrases.orgMoveFailed, { status: reply?.status ?? 0 }));
      return;
    }
    refresh();
  }


  function toggleMulti(id: string) {
    setMultiIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function beginBatchMove(newParentId: string | null) {
    if (!canWrite || multiIds.size === 0) return;
    const ids = [...multiIds];
    if (newParentId && multiIds.has(newParentId)) {
      setBatchProblem(phrases.orgMoveCycleDenied);
      return;
    }
    for (const id of ids) {
      if (wouldClientMoveCycle(forest, id, newParentId)) {
        setBatchProblem(phrases.orgMoveCycleDenied);
        return;
      }
    }
    setBatchProblem(null);
    setBatchStep("reviewing");
    const tid = tenantId.trim();
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(`/api/platform/organizations/moves/preview?${q}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ organizationIds: ids, newParentOrganizationId: newParentId }),
    }).catch(() => null);
    if (!reply?.ok) {
      if (reply?.status === 401) {
        window.location.href = "/login";
        return;
      }
      setBatchProblem(fillPhrase(phrases.orgMovePreviewFailed, { status: reply?.status ?? 0 }));
      setBatchStep("idle");
      return;
    }
    const preview = (await reply.json()) as {
      anyCycle?: boolean;
      allInScope?: boolean;
      totalSubtreeOrganizationCount?: number;
      totalDirectMembershipCount?: number;
      items?: { organizationId?: string }[];
    };
    if (preview.anyCycle) {
      setBatchProblem(phrases.orgMoveCycleDenied);
      setBatchStep("idle");
      return;
    }
    if (preview.allInScope === false) {
      setBatchProblem(phrases.orgMoveOutOfScope);
      setBatchStep("idle");
      return;
    }
    setBatchSummary(
      fillPhrase(phrases.orgBatchMoveReviewBody, {
        count: preview.items?.length ?? ids.length,
        to: newParentId || phrases.orgRootLabel,
        subtree: preview.totalSubtreeOrganizationCount ?? 0,
        members: preview.totalDirectMembershipCount ?? 0,
      }),
    );
    setBatchTarget(newParentId ?? "");
    setBatchStep("reviewing");
  }

  async function confirmBatchMove() {
    if (!canWrite || multiIds.size === 0) return;
    setBatchStep("saving");
    setBatchProblem(null);
    const tid = tenantId.trim();
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const newParentId = batchTarget.trim() ? batchTarget.trim() : null;
    const reply = await fetch(`/api/platform/organizations/moves?${q}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        organizationIds: [...multiIds],
        newParentOrganizationId: newParentId,
      }),
    }).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setBatchProblem(fillPhrase(phrases.orgMoveFailed, { status: reply?.status ?? 0 }));
      setBatchStep("reviewing");
      return;
    }
    setMultiIds(new Set());
    setBatchStep("idle");
    setBatchSummary(null);
    refresh();
  }


  async function beginMove(movedId: string, newParentId: string | null) {
    if (!canWrite) return;
    const current = findOrgNode(forest, movedId);
    const currentParent = current?.parentOrganizationId ?? null;
    if ((currentParent ?? null) === (newParentId ?? null)) return;
    if (wouldClientMoveCycle(forest, movedId, newParentId)) {
      setMoveProblem(phrases.orgMoveCycleDenied);
      setMoveReview(null);
      setMoveStep("idle");
      return;
    }
    setMoveProblem(null);
    setMoveStep("reviewing");
    const tid = tenantId.trim();
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(movedId)}/move/preview?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ newParentOrganizationId: newParentId }),
      },
    ).catch(() => null);
    if (!reply) {
      setMoveProblem(fillPhrase(phrases.orgMovePreviewFailed, { status: 0 }));
      setMoveStep("idle");
      return;
    }
    if (reply.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply.ok) {
      setMoveProblem(fillPhrase(phrases.orgMovePreviewFailed, { status: reply.status }));
      setMoveStep("idle");
      return;
    }
    const preview = (await reply.json()) as MovePreviewPayload;
    if (preview.wouldCycle) {
      setMoveProblem(phrases.orgMoveCycleDenied);
      setMoveReview(null);
      setMoveStep("idle");
      return;
    }
    if (preview.inScope === false) {
      setMoveProblem(phrases.orgMoveOutOfScope);
      setMoveReview(null);
      setMoveStep("idle");
      return;
    }
    setMoveReview({ movedId, newParentId, preview });
    setMoveStep("reviewing");
  }

  async function confirmMove() {
    if (!canWrite || !moveReview) return;
    setMoveStep("saving");
    setMoveProblem(null);
    const tid = tenantId.trim();
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(moveReview.movedId)}/move?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ newParentOrganizationId: moveReview.newParentId }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setMoveProblem(fillPhrase(phrases.orgMoveFailed, { status: reply?.status ?? 0 }));
      setMoveStep("reviewing");
      return;
    }
    setMoveReview(null);
    setMoveStep("idle");
    refresh();
  }

  function reviewCreateChild() {
    if (!canWrite || !selectedId) return;
    const id = normalizeOrgId(childId);
    if (!id) {
      setChildProblem(childId.trim() ? phrases.orgIdInvalid : phrases.orgUnitIdRequired);
      return;
    }
    if (!childName.trim()) {
      setChildProblem(phrases.orgUnitNameRequired);
      return;
    }
    setChildProblem(null);
    setChildStep("reviewing");
  }

  async function confirmCreateChild() {
    if (!canWrite || !selectedId) return;
    const id = normalizeOrgId(childId);
    if (!id) return;
    setChildStep("saving");
    setChildProblem(null);
    const tid = tenantId.trim();
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(id)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          parentOrganizationId: selectedId,
          organizationName: childName.trim(),
          organizationState: ORG_UNIT_STATE_ACTIVE,
        }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setChildProblem(fillPhrase(phrases.orgUnitSaveFailed, { status: reply?.status ?? 0 }));
      setChildStep("reviewing");
      return;
    }
    setPanelNotice(fillPhrase(phrases.orgUnitSavedNotice, { id, name: childName.trim() }));
    setChildId("");
    setChildName("");
    setChildStep("editing");
    refresh();
  }

  async function confirmToggle() {
    if (!canWrite || !selected) return;
    setToggleStep("saving");
    setToggleProblem(null);
    const tid = tenantId.trim();
    const nextState = toggledOrgState(selected.organizationState);
    const body: {
      parentOrganizationId?: string;
      organizationName: string;
      organizationState: string;
    } = {
      organizationName: selected.organizationName,
      organizationState: nextState,
    };
    if (selected.parentOrganizationId) body.parentOrganizationId = selected.parentOrganizationId;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(selected.organizationId)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setToggleProblem(fillPhrase(phrases.orgUnitSaveFailed, { status: reply?.status ?? 0 }));
      setToggleStep("reviewing");
      return;
    }
    setToggleStep("editing");
    setPanelNotice(
      fillPhrase(phrases.orgUnitToggleReview, {
        id: selected.organizationId,
        state: nextState,
      }),
    );
    refresh();
  }

  function reviewAddMember() {
    if (!canWrite || !selectedId) return;
    const subject = normalizeOrgId(memberSubject);
    if (!subject) {
      setMemberProblem(memberSubject.trim() ? phrases.orgIdInvalid : phrases.orgSubjectIdRequired);
      return;
    }
    setMemberProblem(null);
    setMemberStep("reviewing");
  }

  async function confirmAddMember() {
    if (!canWrite || !selectedId) return;
    const subject = normalizeOrgId(memberSubject);
    if (!subject) return;
    setMemberStep("saving");
    setMemberProblem(null);
    const tid = tenantId.trim();
    const reply = await fetch(
      `/api/platform/organizations/memberships?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          subjectId: subject,
          organizationId: selectedId,
          membershipState: ORG_MEMBERSHIP_STATE_ACTIVE,
        }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setMemberProblem(fillPhrase(phrases.orgMembershipSaveFailed, { status: reply?.status ?? 0 }));
      setMemberStep("reviewing");
      return;
    }
    setPanelNotice(fillPhrase(phrases.orgMembershipSavedNotice, { subject, unit: selectedId }));
    setMemberSubject("");
    setMemberStep("editing");
    refresh();
  }

  async function confirmRemoveMember() {
    if (!canWrite || !selectedId || !removeSubject) return;
    setRemoveStep("saving");
    setRemoveProblem(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({
      tenantId: tid,
      subjectId: removeSubject,
      organizationId: selectedId,
    });
    const reply = await fetch(`/api/platform/organizations/memberships?${q.toString()}`, {
      method: "DELETE",
    }).catch(() => null);
    if (reply?.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply?.ok) {
      setRemoveProblem(fillPhrase(phrases.orgMembershipRemoveFailed, { status: reply?.status ?? 0 }));
      setRemoveStep("reviewing");
      return;
    }
    setRemoveSubject(null);
    setRemoveStep("editing");
    setPanelNotice(
      fillPhrase(phrases.orgMembershipRemoveReview, { subject: removeSubject, unit: selectedId }),
    );
    refresh();
  }


  function parseCsvIds(raw: string): string[] {
    return raw
      .split(/[,，\s]+/)
      .map((s) => s.trim())
      .filter(Boolean);
  }

  async function previewMerge() {
    if (!canWrite || !selectedId) return;
    const target = normalizeOrgId(mergeTarget);
    if (!target) {
      setMergeProblem(phrases.orgMergeTargetLabel);
      return;
    }
    setMergeProblem(null);
    setMergeSummary(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({ tenantId: tid });
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(selectedId)}/merge/preview?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ targetOrganizationId: target }),
      },
    ).catch(() => null);
    if (!reply?.ok) {
      setMergeProblem(fillPhrase(phrases.orgMergePreviewFailed, { status: reply?.status ?? 0 }));
      return;
    }
    const preview = (await reply.json()) as {
      wouldCycle?: boolean;
      inScope?: boolean;
      movedMembershipCount?: number;
      reparentedChildCount?: number;
      sourceSubtreeOrganizationCount?: number;
    };
    if (preview.wouldCycle) {
      setMergeProblem(phrases.orgMergeCycleDenied);
      return;
    }
    if (preview.inScope === false) {
      setMergeProblem(phrases.orgMergeOutOfScope);
      return;
    }
    setMergeSummary(
      fillPhrase(phrases.orgMergeReviewBody, {
        source: selectedId,
        target,
        members: preview.movedMembershipCount ?? 0,
        children: preview.reparentedChildCount ?? 0,
        subtree: preview.sourceSubtreeOrganizationCount ?? 0,
      }),
    );
    setMergeStep("reviewing");
  }

  async function confirmMerge() {
    if (!canWrite || !selectedId) return;
    const target = normalizeOrgId(mergeTarget);
    if (!target) return;
    setMergeStep("saving");
    setMergeProblem(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({ tenantId: tid });
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(selectedId)}/merge?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ targetOrganizationId: target }),
      },
    ).catch(() => null);
    if (!reply?.ok) {
      setMergeProblem(fillPhrase(phrases.orgMergeFailed, { status: reply?.status ?? 0 }));
      setMergeStep("reviewing");
      return;
    }
    setPanelNotice(fillPhrase(phrases.orgMergeDoneNotice, { source: selectedId, target }));
    setMergeStep("editing");
    setMergeTarget("");
    setMergeSummary(null);
    setSelectedId(target);
    onMoved?.();
  }

  async function previewSplit() {
    if (!canWrite || !selectedId) return;
    const nid = normalizeOrgId(splitId);
    if (!nid || !splitName.trim()) {
      setSplitProblem(phrases.orgSplitNewIdLabel);
      return;
    }
    setSplitProblem(null);
    setSplitSummary(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({ tenantId: tid });
    const children = parseCsvIds(splitChildren);
    const members = parseCsvIds(splitMembers);
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(selectedId)}/split/preview?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          newOrganizationId: nid,
          newOrganizationName: splitName.trim(),
          childOrganizationIds: children,
          memberSubjectIds: members,
        }),
      },
    ).catch(() => null);
    if (!reply?.ok) {
      setSplitProblem(fillPhrase(phrases.orgSplitPreviewFailed, { status: reply?.status ?? 0 }));
      return;
    }
    const preview = (await reply.json()) as {
      childrenValid?: boolean;
      inScope?: boolean;
      newParentOrganizationId?: string | null;
      childCount?: number;
      memberCount?: number;
    };
    if (preview.childrenValid === false) {
      setSplitProblem(phrases.orgSplitInvalidChildren);
      return;
    }
    if (preview.inScope === false) {
      setSplitProblem(phrases.orgSplitOutOfScope);
      return;
    }
    setSplitSummary(
      fillPhrase(phrases.orgSplitReviewBody, {
        source: selectedId,
        newId: nid,
        parent: preview.newParentOrganizationId || phrases.orgRootLabel,
        children: preview.childCount ?? children.length,
        members: preview.memberCount ?? members.length,
      }),
    );
    setSplitStep("reviewing");
  }

  async function confirmSplit() {
    if (!canWrite || !selectedId) return;
    const nid = normalizeOrgId(splitId);
    if (!nid) return;
    setSplitStep("saving");
    setSplitProblem(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({ tenantId: tid });
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(selectedId)}/split?${q}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          newOrganizationId: nid,
          newOrganizationName: splitName.trim(),
          childOrganizationIds: parseCsvIds(splitChildren),
          memberSubjectIds: parseCsvIds(splitMembers),
        }),
      },
    ).catch(() => null);
    if (!reply?.ok) {
      setSplitProblem(fillPhrase(phrases.orgSplitFailed, { status: reply?.status ?? 0 }));
      setSplitStep("reviewing");
      return;
    }
    setPanelNotice(fillPhrase(phrases.orgSplitDoneNotice, { newId: nid }));
    setSplitStep("editing");
    setSplitId("");
    setSplitName("");
    setSplitChildren("");
    setSplitMembers("");
    setSplitSummary(null);
    setSelectedId(nid);
    onMoved?.();
  }

  async function confirmManager() {
    if (!canWrite || !selectedId) return;
    setManagerStep("saving");
    setManagerProblem(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({ tenantId: tid });
    let reply: Response | null = null;
    if (managerMode === "clear") {
      reply = await fetch(
        `/api/platform/organizations/${encodeURIComponent(selectedId)}/manager?${q}`,
        { method: "DELETE" },
      ).catch(() => null);
    } else {
      const sid = managerSubject.trim();
      if (!sid) {
        setManagerProblem(phrases.orgManagerLabel);
        setManagerStep("editing");
        return;
      }
      reply = await fetch(
        `/api/platform/organizations/${encodeURIComponent(selectedId)}/manager?${q}`,
        {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ managerSubjectId: sid }),
        },
      ).catch(() => null);
    }
    if (!reply?.ok) {
      setManagerProblem(fillPhrase(phrases.orgManagerFailed, { status: reply?.status ?? 0 }));
      setManagerStep("reviewing");
      return;
    }
    setPanelNotice(phrases.orgManagerSavedNotice);
    setManagerStep("editing");
    setManagerSubject("");
    onMoved?.();
  }


  const selected = selectedId ? findOrgNode(forest, selectedId) : null;
  const selectedMembers = useMemo(() => {
    if (!selectedId) return [];
    return memberships.filter((m) => (m.organizationId ?? "").trim() === selectedId);
  }, [memberships, selectedId]);

  if (organizations.length === 0) {
    return <p className="mt-3 text-sm text-muted">{phrases.emptyList}</p>;
  }

  return (
    <div className="mt-3 flex flex-col gap-3">
      <div className="flex flex-wrap gap-1" role="tablist" aria-label={phrases.orgTreeTitle}>
        <button
          type="button"
          role="tab"
          aria-selected={vizMode === "tree"}
          onClick={() => setVizMode("tree")}
          className={
            vizMode === "tree"
              ? "rounded-md bg-foreground px-2 py-1 text-xs text-background"
              : "rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          }
        >
          {phrases.orgVizTree}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={vizMode === "canvas"}
          onClick={() => setVizMode("canvas")}
          className={
            vizMode === "canvas"
              ? "rounded-md bg-foreground px-2 py-1 text-xs text-background"
              : "rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          }
        >
          {phrases.orgVizCanvas}
        </button>
      </div>


      {canWrite && multiIds.size > 0 ? (
        <div className="rounded-lg border border-border bg-surface p-3 text-xs">
          <p className="font-medium">
            {fillPhrase(phrases.orgBatchMoveSelected, { count: multiIds.size })}
          </p>
          <p className="mt-1 text-muted">{phrases.orgBatchMoveHint}</p>
          {batchStep === "idle" || batchStep === "reviewing" ? (
            <div className="mt-2 flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1">
                {phrases.orgBatchMoveTargetLabel}
                <select
                  value={batchTarget}
                  onChange={(e) => setBatchTarget(e.target.value)}
                  className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                  disabled={batchStep === "reviewing"}
                >
                  <option value="">{phrases.orgRootLabel}</option>
                  {organizations
                    .filter((o) => o.organizationId && !multiIds.has(o.organizationId))
                    .map((o) => (
                      <option key={o.organizationId} value={o.organizationId}>
                        {o.organizationName} ({o.organizationId})
                      </option>
                    ))}
                </select>
              </label>
              {batchStep === "idle" ? (
                <button
                  type="button"
                  onClick={() => void beginBatchMove(batchTarget.trim() ? batchTarget.trim() : null)}
                  className="rounded-md bg-foreground px-3 py-1 text-background"
                >
                  {phrases.orgBatchMovePreviewAction}
                </button>
              ) : null}
              <button
                type="button"
                onClick={() => {
                  setMultiIds(new Set());
                  setBatchStep("idle");
                  setBatchProblem(null);
                  setBatchSummary(null);
                }}
                className="rounded-md border border-border px-3 py-1"
              >
                {phrases.orgBatchMoveClear}
              </button>
            </div>
          ) : null}
          {batchProblem ? (
            <p role="alert" className="mt-2 text-red-700">
              {batchProblem}
            </p>
          ) : null}
          {batchStep !== "idle" && batchSummary ? (
            <div className="mt-2 space-y-2">
              <p>{batchSummary}</p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => void confirmBatchMove()}
                  disabled={batchStep === "saving"}
                  className="rounded-md bg-foreground px-3 py-1 text-background disabled:opacity-60"
                >
                  {batchStep === "saving" ? phrases.savingAction : phrases.confirmSaveAction}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setBatchStep("idle");
                    setBatchSummary(null);
                  }}
                  disabled={batchStep === "saving"}
                  className="rounded-md border border-border px-3 py-1"
                >
                  {phrases.cancelAction}
                </button>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}

      {canWrite && vizMode === "tree" ? (
        <p className="text-xs text-muted">{phrases.orgMoveHint}</p>
      ) : null}

      {moveProblem ? (
        <p role="alert" className="text-xs text-red-700">
          {moveProblem}
        </p>
      ) : null}

      {moveReview && moveStep !== "idle" ? (
        <div className="rounded-lg border border-border bg-surface p-3 text-xs">
          <p className="font-medium">{phrases.orgMoveReviewTitle}</p>
          <p className="mt-1">
            {fillPhrase(phrases.orgMoveReviewBody, {
              id: moveReview.movedId,
              from: moveReview.preview.fromParentOrganizationId || phrases.orgRootLabel,
              to: moveReview.newParentId || phrases.orgRootLabel,
              subtree: moveReview.preview.subtreeOrganizationCount ?? 0,
              members: moveReview.preview.directMembershipCount ?? 0,
            })}
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => void confirmMove()}
              disabled={moveStep === "saving"}
              className="rounded-md bg-foreground px-3 py-1 text-background disabled:opacity-60"
            >
              {moveStep === "saving" ? phrases.savingAction : phrases.confirmSaveAction}
            </button>
            <button
              type="button"
              onClick={() => {
                setMoveReview(null);
                setMoveStep("idle");
                setMoveProblem(null);
              }}
              disabled={moveStep === "saving"}
              className="rounded-md border border-border px-3 py-1"
            >
              {phrases.cancelAction}
            </button>
          </div>
        </div>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(16rem,1fr)]">
        <div className="flex min-h-[14rem] flex-col gap-2 rounded-lg border border-border bg-surface p-3">
          {vizMode === "tree" ? (
            <>
              <label className="flex flex-col gap-1 text-xs">
                <span className="text-muted">{phrases.orgTreeSearchLabel}</span>
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={phrases.orgTreeSearchPlaceholder}
                  className="rounded-md border border-border bg-background px-2 py-1"
                  aria-label={phrases.orgTreeSearchLabel}
                  autoComplete="off"
                />
              </label>
              {query.trim() ? (
                <p className="text-xs text-muted" role="status">
                  {hits.size === 0
                    ? phrases.orgTreeSearchEmpty
                    : fillPhrase(phrases.orgTreeSearchHits, { count: hits.size })}
                </p>
              ) : null}
              {canWrite ? (
                <div
                  className={[
                    "rounded-md border border-dashed px-2 py-2 text-center text-xs",
                    rootDragOver ? "border-foreground bg-foreground/5" : "border-border text-muted",
                  ].join(" ")}
                  onDragOver={(e) => {
                    e.preventDefault();
                    e.dataTransfer.dropEffect = "move";
                    setRootDragOver(true);
                  }}
                  onDragLeave={() => setRootDragOver(false)}
                  onDrop={(e) => {
                    e.preventDefault();
                    setRootDragOver(false);
                    const movedId =
                      e.dataTransfer.getData("text/org-id") || e.dataTransfer.getData("text/plain");
                    if (movedId) void beginMove(movedId.trim(), null);
                  }}
                >
                  {phrases.orgMoveDropRoot}
                </div>
              ) : null}
              <div
                ref={listRef}
                className="max-h-[28rem] overflow-auto rounded-md border border-border bg-background p-1"
                role="tree"
                aria-label={phrases.orgTreeTitle}
              >
                {forest.map((node) => (
                  <TreeBranch
                    key={node.organizationId}
                    node={node}
                    depth={0}
                    expanded={expanded}
                    hits={hits}
                    selectedId={selectedId}
                    queryActive={Boolean(query.trim())}
                    dragOverId={dragOverId}
                    canWrite={canWrite}
                    multiIds={multiIds}
                    phrases={phrases}
                    onToggle={toggleExpand}
                    onSelect={selectNode}
                    onToggleMulti={toggleMulti}
                    onDragOverId={setDragOverId}
                    onDropOn={(targetId, movedId) => void beginMove(movedId, targetId)}
                  />
                ))}
              </div>
            </>
          ) : (
            <OrgCanvas
              phrases={phrases}
              forest={forest}
              selectedId={selectedId}
              onSelect={selectNode}
              exportBasename={`org-${tenantId.trim() || "chart"}`}
            />
          )}
        </div>

        <aside
          className="rounded-lg border border-border bg-surface p-4 text-sm"
          aria-label={phrases.orgTreePanelTitle}
        >
          <h3 className="text-sm font-medium">{phrases.orgTreePanelTitle}</h3>
          {panelNotice ? (
            <p role="status" className="mt-2 text-xs text-green-700">
              {panelNotice}
            </p>
          ) : null}
          {!selected ? (
            <p className="mt-2 text-xs text-muted">{phrases.orgTreePanelEmpty}</p>
          ) : (
            <div className="mt-3 space-y-4 text-xs">
              <dl className="space-y-2">
                <div>
                  <dt className="text-muted">{phrases.orgUnitIdLabel}</dt>
                  <dd className="font-mono">{selected.organizationId}</dd>
                </div>
                <div>
                  <dt className="text-muted">{phrases.orgNameLabel}</dt>
                  <dd>{selected.organizationName}</dd>
                </div>
                <div>
                  <dt className="text-muted">{phrases.orgParentLabel}</dt>
                  <dd className="font-mono">
                    {selected.parentOrganizationId || phrases.orgRootLabel}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted">{phrases.orgStateLabel}</dt>
                  <dd>{selected.organizationState}</dd>
                </div>
              </dl>

              {canWrite ? (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="font-medium">{phrases.orgPanelToggleTitle}</p>
                  {toggleStep === "editing" ? (
                    <button
                      type="button"
                      onClick={() => {
                        setToggleProblem(null);
                        setToggleStep("reviewing");
                      }}
                      className="rounded-md border border-border px-2 py-1 hover:bg-background"
                    >
                      {isOrgDisabledState(selected.organizationState)
                        ? phrases.enableAction
                        : phrases.disableAction}
                    </button>
                  ) : (
                    <div className="space-y-1">
                      <p>
                        {fillPhrase(phrases.orgUnitToggleReview, {
                          id: selected.organizationId,
                          state: toggledOrgState(selected.organizationState),
                        })}
                      </p>
                      {toggleProblem ? (
                        <p role="alert" className="text-red-700">
                          {toggleProblem}
                        </p>
                      ) : null}
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => void confirmToggle()}
                          disabled={toggleStep === "saving"}
                          className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                        >
                          {toggleStep === "saving"
                            ? phrases.savingAction
                            : phrases.confirmSaveAction}
                        </button>
                        <button
                          type="button"
                          onClick={() => setToggleStep("editing")}
                          disabled={toggleStep === "saving"}
                          className="rounded-md border border-border px-2 py-0.5"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ) : null}


              {canWrite && selected.parentOrganizationId ? (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="font-medium">{phrases.orgSiblingOrderTitle}</p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => void moveSibling(-1)}
                      className="rounded-md border border-border px-2 py-1 hover:bg-background"
                    >
                      {phrases.orgSiblingMoveUp}
                    </button>
                    <button
                      type="button"
                      onClick={() => void moveSibling(1)}
                      className="rounded-md border border-border px-2 py-1 hover:bg-background"
                    >
                      {phrases.orgSiblingMoveDown}
                    </button>
                  </div>
                </div>
              ) : null}

              <div className="space-y-2 border-t border-border pt-3">
                <p className="font-medium">{phrases.orgManagerTitle}</p>
                <p className="text-muted">{phrases.orgManagerHint}</p>
                <p>
                  <span className="text-muted">{phrases.orgManagerLabel}: </span>
                  <span className="font-mono">
                    {selected.managerSubjectId || phrases.orgManagerNone}
                  </span>
                </p>
                {canWrite ? (
                  managerStep === "editing" ? (
                    <div className="flex flex-col gap-2">
                      <label className="flex flex-col gap-1">
                        {phrases.orgManagerLabel}
                        <input
                          value={managerSubject}
                          onChange={(e) => setManagerSubject(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                          placeholder={selected.managerSubjectId || ""}
                        />
                      </label>
                      {managerProblem ? (
                        <p role="alert" className="text-red-700">
                          {managerProblem}
                        </p>
                      ) : null}
                      <div className="flex flex-wrap gap-1">
                        <button
                          type="button"
                          onClick={() => {
                            setManagerProblem(null);
                            setManagerMode("assign");
                            setManagerStep("reviewing");
                          }}
                          className="rounded-md bg-foreground px-2 py-1 text-background"
                        >
                          {phrases.orgManagerAssignAction}
                        </button>
                        {selected.managerSubjectId ? (
                          <button
                            type="button"
                            onClick={() => {
                              setManagerProblem(null);
                              setManagerMode("clear");
                              setManagerStep("reviewing");
                            }}
                            className="rounded-md border border-border px-2 py-1"
                          >
                            {phrases.orgManagerClearAction}
                          </button>
                        ) : null}
                      </div>
                    </div>
                  ) : (
                    <div className="space-y-1">
                      <p>
                        {managerMode === "clear"
                          ? fillPhrase(phrases.orgManagerReviewClear, {
                              unit: selected.organizationId,
                            })
                          : fillPhrase(phrases.orgManagerReviewAssign, {
                              subject: managerSubject.trim(),
                              unit: selected.organizationId,
                            })}
                      </p>
                      {managerProblem ? (
                        <p role="alert" className="text-red-700">
                          {managerProblem}
                        </p>
                      ) : null}
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => void confirmManager()}
                          disabled={managerStep === "saving"}
                          className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                        >
                          {managerStep === "saving"
                            ? phrases.savingAction
                            : phrases.confirmSaveAction}
                        </button>
                        <button
                          type="button"
                          onClick={() => setManagerStep("editing")}
                          disabled={managerStep === "saving"}
                          className="rounded-md border border-border px-2 py-0.5"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  )
                ) : null}
              </div>

              {canWrite ? (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="font-medium">{phrases.orgMergeTitle}</p>
                  <p className="text-muted">{phrases.orgMergeHint}</p>
                  {mergeStep === "editing" ? (
                    <div className="flex flex-col gap-2">
                      <label className="flex flex-col gap-1">
                        {phrases.orgMergeTargetLabel}
                        <input
                          value={mergeTarget}
                          onChange={(e) => setMergeTarget(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                        />
                      </label>
                      {mergeProblem ? (
                        <p role="alert" className="text-red-700">
                          {mergeProblem}
                        </p>
                      ) : null}
                      <button
                        type="button"
                        onClick={() => void previewMerge()}
                        className="self-start rounded-md bg-foreground px-2 py-1 text-background"
                      >
                        {phrases.orgMergePreviewAction}
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-1">
                      <p>{mergeSummary}</p>
                      {mergeProblem ? (
                        <p role="alert" className="text-red-700">
                          {mergeProblem}
                        </p>
                      ) : null}
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => void confirmMerge()}
                          disabled={mergeStep === "saving"}
                          className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                        >
                          {mergeStep === "saving"
                            ? phrases.savingAction
                            : phrases.confirmSaveAction}
                        </button>
                        <button
                          type="button"
                          onClick={() => setMergeStep("editing")}
                          disabled={mergeStep === "saving"}
                          className="rounded-md border border-border px-2 py-0.5"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ) : null}

              {canWrite ? (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="font-medium">{phrases.orgSplitTitle}</p>
                  <p className="text-muted">{phrases.orgSplitHint}</p>
                  {splitStep === "editing" ? (
                    <div className="flex flex-col gap-2">
                      <label className="flex flex-col gap-1">
                        {phrases.orgSplitNewIdLabel}
                        <input
                          value={splitId}
                          onChange={(e) => setSplitId(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                        />
                      </label>
                      <label className="flex flex-col gap-1">
                        {phrases.orgSplitNewNameLabel}
                        <input
                          value={splitName}
                          onChange={(e) => setSplitName(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1"
                          autoComplete="off"
                        />
                      </label>
                      <label className="flex flex-col gap-1">
                        {phrases.orgSplitChildrenLabel}
                        <input
                          value={splitChildren}
                          onChange={(e) => setSplitChildren(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                        />
                      </label>
                      <label className="flex flex-col gap-1">
                        {phrases.orgSplitMembersLabel}
                        <input
                          value={splitMembers}
                          onChange={(e) => setSplitMembers(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                        />
                      </label>
                      {splitProblem ? (
                        <p role="alert" className="text-red-700">
                          {splitProblem}
                        </p>
                      ) : null}
                      <button
                        type="button"
                        onClick={() => void previewSplit()}
                        className="self-start rounded-md bg-foreground px-2 py-1 text-background"
                      >
                        {phrases.orgSplitPreviewAction}
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-1">
                      <p>{splitSummary}</p>
                      {splitProblem ? (
                        <p role="alert" className="text-red-700">
                          {splitProblem}
                        </p>
                      ) : null}
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => void confirmSplit()}
                          disabled={splitStep === "saving"}
                          className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                        >
                          {splitStep === "saving"
                            ? phrases.savingAction
                            : phrases.confirmSaveAction}
                        </button>
                        <button
                          type="button"
                          onClick={() => setSplitStep("editing")}
                          disabled={splitStep === "saving"}
                          className="rounded-md border border-border px-2 py-0.5"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ) : null}

              {canWrite ? (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="font-medium">{phrases.orgPanelCreateChildTitle}</p>
                  <p className="text-muted">{phrases.orgPanelCreateChildHint}</p>
                  {childStep === "editing" ? (
                    <div className="flex flex-col gap-2">
                      <label className="flex flex-col gap-1">
                        {phrases.orgUnitIdLabel}
                        <input
                          value={childId}
                          onChange={(e) => setChildId(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                          autoComplete="off"
                        />
                      </label>
                      <label className="flex flex-col gap-1">
                        {phrases.orgNameLabel}
                        <input
                          value={childName}
                          onChange={(e) => setChildName(e.target.value)}
                          className="rounded-md border border-border bg-background px-2 py-1"
                          autoComplete="off"
                        />
                      </label>
                      {childProblem ? (
                        <p role="alert" className="text-red-700">
                          {childProblem}
                        </p>
                      ) : null}
                      <button
                        type="button"
                        onClick={reviewCreateChild}
                        className="self-start rounded-md bg-foreground px-2 py-1 text-background"
                      >
                        {phrases.reviewChangeAction}
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-1">
                      <p>
                        {phrases.orgUnitIdLabel}:{" "}
                        <span className="font-mono">{normalizeOrgId(childId) ?? childId}</span>
                      </p>
                      <p>
                        {phrases.orgParentLabel}:{" "}
                        <span className="font-mono">{selected.organizationId}</span>
                      </p>
                      <p>
                        {phrases.orgNameLabel}: {childName.trim()}
                      </p>
                      {childProblem ? (
                        <p role="alert" className="text-red-700">
                          {childProblem}
                        </p>
                      ) : null}
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => void confirmCreateChild()}
                          disabled={childStep === "saving"}
                          className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                        >
                          {childStep === "saving"
                            ? phrases.savingAction
                            : phrases.confirmSaveAction}
                        </button>
                        <button
                          type="button"
                          onClick={() => setChildStep("editing")}
                          disabled={childStep === "saving"}
                          className="rounded-md border border-border px-2 py-0.5"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ) : null}

              <div className="space-y-2 border-t border-border pt-3">
                <p className="font-medium">{phrases.orgTreeDirectMembers}</p>
                {selectedMembers.length === 0 ? (
                  <p className="text-muted">{phrases.orgTreeNoMembers}</p>
                ) : (
                  <ul className="space-y-1">
                    {selectedMembers.map((m) => {
                      const sid = m.subjectId ?? "";
                      const reviewing =
                        removeSubject === sid && removeStep !== "editing";
                      return (
                        <li key={`${sid}/${m.organizationId}`} className="font-mono">
                          {sid}
                          {m.membershipState ? ` (${m.membershipState})` : ""}
                          {canWrite ? (
                            reviewing ? (
                              <div className="mt-1 space-y-1 font-sans normal-case">
                                <p>
                                  {fillPhrase(phrases.orgMembershipRemoveReview, {
                                    subject: sid,
                                    unit: selected.organizationId,
                                  })}
                                </p>
                                {removeProblem ? (
                                  <p role="alert" className="text-red-700">
                                    {removeProblem}
                                  </p>
                                ) : null}
                                <div className="flex gap-1">
                                  <button
                                    type="button"
                                    onClick={() => void confirmRemoveMember()}
                                    disabled={removeStep === "saving"}
                                    className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                                  >
                                    {removeStep === "saving"
                                      ? phrases.savingAction
                                      : phrases.orgMembershipRemoveConfirm}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setRemoveSubject(null);
                                      setRemoveStep("editing");
                                    }}
                                    disabled={removeStep === "saving"}
                                    className="rounded-md border border-border px-2 py-0.5"
                                  >
                                    {phrases.cancelAction}
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button
                                type="button"
                                className="ml-2 rounded border border-border px-1 font-sans text-[10px]"
                                onClick={() => {
                                  setRemoveProblem(null);
                                  setRemoveSubject(sid);
                                  setRemoveStep("reviewing");
                                }}
                              >
                                {phrases.orgMembershipRemoveAction}
                              </button>
                            )
                          ) : null}
                        </li>
                      );
                    })}
                  </ul>
                )}

                {canWrite ? (
                  <div className="mt-2 space-y-2">
                    <p className="font-medium font-sans">{phrases.orgPanelAddMemberTitle}</p>
                    {memberStep === "editing" ? (
                      <div className="flex flex-col gap-2 font-sans">
                        <label className="flex flex-col gap-1">
                          {phrases.orgSubjectLabel}
                          <input
                            value={memberSubject}
                            onChange={(e) => setMemberSubject(e.target.value)}
                            className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                            autoComplete="off"
                          />
                        </label>
                        {memberProblem ? (
                          <p role="alert" className="text-red-700">
                            {memberProblem}
                          </p>
                        ) : null}
                        <button
                          type="button"
                          onClick={reviewAddMember}
                          className="self-start rounded-md bg-foreground px-2 py-1 text-background"
                        >
                          {phrases.reviewChangeAction}
                        </button>
                      </div>
                    ) : (
                      <div className="space-y-1 font-sans">
                        <p>
                          {phrases.orgSubjectLabel}:{" "}
                          <span className="font-mono">
                            {normalizeOrgId(memberSubject) ?? memberSubject}
                          </span>
                        </p>
                        <p>
                          {phrases.orgUnitIdLabel}:{" "}
                          <span className="font-mono">{selected.organizationId}</span>
                        </p>
                        {memberProblem ? (
                          <p role="alert" className="text-red-700">
                            {memberProblem}
                          </p>
                        ) : null}
                        <div className="flex gap-1">
                          <button
                            type="button"
                            onClick={() => void confirmAddMember()}
                            disabled={memberStep === "saving"}
                            className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                          >
                            {memberStep === "saving"
                              ? phrases.savingAction
                              : phrases.confirmSaveAction}
                          </button>
                          <button
                            type="button"
                            onClick={() => setMemberStep("editing")}
                            disabled={memberStep === "saving"}
                            className="rounded-md border border-border px-2 py-0.5"
                          >
                            {phrases.cancelAction}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                ) : null}
              </div>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

type BranchProps = {
  node: OrgTreeNode;
  depth: number;
  expanded: Set<string>;
  hits: Set<string>;
  selectedId: string | null;
  queryActive: boolean;
  dragOverId: string | null;
  canWrite: boolean;
  multiIds: Set<string>;
  phrases: PhraseBook;
  onToggle: (id: string) => void;
  onSelect: (id: string) => void;
  onToggleMulti: (id: string) => void;
  onDragOverId: (id: string | null) => void;
  onDropOn: (targetId: string, movedId: string) => void;
};

function TreeBranch({
  node,
  depth,
  expanded,
  hits,
  selectedId,
  queryActive,
  dragOverId,
  canWrite,
  multiIds,
  phrases,
  onToggle,
  onSelect,
  onToggleMulti,
  onDragOverId,
  onDropOn,
}: BranchProps) {
  const hasChildren = node.children.length > 0;
  const isOpen = expanded.has(node.organizationId);
  const isHit = hits.has(node.organizationId);
  const isSelected = selectedId === node.organizationId;
  const disabled = isOrgDisabledState(node.organizationState);
  const isDropTarget = dragOverId === node.organizationId;

  return (
    <div role="treeitem" aria-expanded={hasChildren ? isOpen : undefined}>
      <div
        data-org-id={node.organizationId}
        draggable={canWrite}
        onDragStart={(e) => {
          if (!canWrite) return;
          e.dataTransfer.setData("text/org-id", node.organizationId);
          e.dataTransfer.setData("text/plain", node.organizationId);
          e.dataTransfer.effectAllowed = "move";
        }}
        onDragOver={(e) => {
          if (!canWrite) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = "move";
          onDragOverId(node.organizationId);
        }}
        onDragLeave={() => onDragOverId(null)}
        onDrop={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onDragOverId(null);
          const movedId = e.dataTransfer.getData("text/org-id") || e.dataTransfer.getData("text/plain");
          if (movedId && movedId.trim() !== node.organizationId) {
            onDropOn(node.organizationId, movedId.trim());
          }
        }}
        className={[
          "flex items-center gap-1 rounded px-1 py-0.5 text-sm",
          isSelected ? "bg-foreground/10" : "hover:bg-background",
          isHit && queryActive ? "ring-1 ring-amber-500/60" : "",
          isDropTarget ? "ring-1 ring-foreground" : "",
          disabled ? "opacity-60" : "",
          canWrite ? "cursor-grab active:cursor-grabbing" : "",
        ]
          .filter(Boolean)
          .join(" ")}
        style={{ paddingLeft: `${depth * 0.85 + 0.25}rem` }}
      >
        {canWrite ? (
          <input
            type="checkbox"
            className="h-3.5 w-3.5 shrink-0"
            checked={multiIds.has(node.organizationId)}
            onChange={() => onToggleMulti(node.organizationId)}
            onClick={(e) => e.stopPropagation()}
            aria-label={phrases.orgBatchMoveSelect}
          />
        ) : null}
        {hasChildren ? (
          <button
            type="button"
            className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded border border-border text-[10px] leading-none"
            aria-label={isOpen ? phrases.orgTreeCollapse : phrases.orgTreeExpand}
            onClick={() => onToggle(node.organizationId)}
          >
            {isOpen ? "−" : "+"}
          </button>
        ) : (
          <span className="inline-block h-5 w-5 shrink-0" aria-hidden />
        )}
        <button
          type="button"
          className="min-w-0 flex-1 truncate text-left"
          onClick={() => onSelect(node.organizationId)}
        >
          <span className={isHit && queryActive ? "font-semibold" : ""}>
            {node.organizationName}
          </span>
          <span className="ml-2 font-mono text-[10px] text-muted">{node.organizationId}</span>
        </button>
      </div>
      {hasChildren && isOpen
        ? node.children.map((child) => (
            <TreeBranch
              key={child.organizationId}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              hits={hits}
              selectedId={selectedId}
              queryActive={queryActive}
              dragOverId={dragOverId}
              canWrite={canWrite}
              multiIds={multiIds}
              phrases={phrases}
              onToggle={onToggle}
              onSelect={onSelect}
              onToggleMulti={onToggleMulti}
              onDragOverId={onDragOverId}
              onDropOn={onDropOn}
            />
          ))
        : null}
    </div>
  );
}
