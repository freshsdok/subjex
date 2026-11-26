"use client";

import { useCallback, useEffect, useState } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type Definition = {
  questionnaireId?: string;
  title?: string;
  status?: string;
  revision?: number;
  collectMode?: string;
  items?: Item[];
};

type Item = {
  itemId?: string;
  sortOrder?: number;
  questionType?: string;
  prompt?: string;
  required?: boolean;
  optionsJson?: string;
};

type Stats = {
  responseCount?: number;
  items?: { itemId?: string; prompt?: string; questionType?: string; counts?: Record<string, number> }[];
};

type Assignment = {
  assignmentId?: string;
  questionnaireId?: string;
  assigneeKind?: string;
  assigneeId?: string;
  createdAt?: string;
};

type OrganizationRow = {
  organizationId?: string;
  organizationName?: string;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Thin questionnaire console — list/create/publish/close, assignments, stats/CSV (Q6b).
 * 薄问卷控制台：列表/创建/发布/关闭、分配、汇总/CSV（Q6b）。
 */
export function QuestionnairesConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [rows, setRows] = useState<Definition[]>([]);
  const [selectedId, setSelectedId] = useState<string>("");
  const [detail, setDetail] = useState<Definition | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [newTitle, setNewTitle] = useState("");
  const [newMode, setNewMode] = useState("AUTH_END_USER");
  const [itemPrompt, setItemPrompt] = useState("");
  const [itemType, setItemType] = useState("TEXT_SHORT");
  const [busy, setBusy] = useState(false);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [organizations, setOrganizations] = useState<OrganizationRow[]>([]);
  const [assigneeKind, setAssigneeKind] = useState("SUBJECT");
  const [assigneeId, setAssigneeId] = useState("");

  const tenantQ = () => `tenantId=${encodeURIComponent(tenantId.trim())}`;

  const loadList = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setRows([]);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    setNotice(null);
    const reply = await fetch(`/api/platform/questionnaires?${tenantQ()}`, { cache: "no-store" }).catch(
      () => null,
    );
    if (!reply?.ok) {
      setRows([]);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as { questionnaires?: Definition[] };
    setRows(body.questionnaires ?? []);
    setStatus("ok");
  }, [tenantId]);

  const loadDetail = useCallback(
    async (qid: string) => {
      const tid = tenantId.trim();
      if (!tid || !qid) {
        setDetail(null);
        setStats(null);
        return;
      }
      const [dReply, sReply] = await Promise.all([
        fetch(`/api/platform/questionnaires/${encodeURIComponent(qid)}?${tenantQ()}`, {
          cache: "no-store",
        }).catch(() => null),
        fetch(`/api/platform/questionnaires/${encodeURIComponent(qid)}/stats?${tenantQ()}`, {
          cache: "no-store",
        }).catch(() => null),
      ]);
      if (!dReply?.ok) {
        setDetail(null);
        setAssignments([]);
        setErrorStatus(dReply?.status ?? 0);
        setStatus("error");
        return;
      }
      setDetail((await dReply.json()) as Definition);
      if (sReply?.ok) {
        setStats((await sReply.json()) as Stats);
      } else {
        setStats(null);
      }
      const aReply = await fetch(
        `/api/platform/questionnaires/${encodeURIComponent(qid)}/assignments?${tenantQ()}`,
        { cache: "no-store" },
      ).catch(() => null);
      if (aReply?.ok) {
        const body = (await aReply.json()) as { assignments?: Assignment[] };
        setAssignments(body.assignments ?? []);
      } else {
        setAssignments([]);
      }
      const oReply = await fetch(`/api/platform/organizations?${tenantQ()}`, { cache: "no-store" }).catch(
        () => null,
      );
      if (oReply?.ok) {
        const body = (await oReply.json()) as { organizations?: OrganizationRow[] };
        setOrganizations(body.organizations ?? []);
      } else {
        setOrganizations([]);
      }
    },
    [tenantId],
  );

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      // Yield so loader setState is not synchronous in this effect.
      // 先让出事件循环，避免在 effect 内同步 setState。
      await Promise.resolve();
      if (!cancelled) {
        await loadList();
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadList]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      // Yield so loader setState is not synchronous in this effect.
      // 先让出事件循环，避免在 effect 内同步 setState。
      await Promise.resolve();
      if (!cancelled) {
        if (selectedId) await loadDetail(selectedId);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedId, loadDetail]);

  async function createQuestionnaire() {
    if (!canWrite) return;
    const tid = tenantId.trim();
    const title = newTitle.trim();
    if (!tid || !title) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(`/api/platform/questionnaires?${tenantQ()}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title, collectMode: newMode }),
    }).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const created = (await reply.json()) as Definition;
    setNewTitle("");
    setNotice(phrases.questionnairesCreatedNotice);
    await loadList();
    if (created.questionnaireId) setSelectedId(created.questionnaireId);
  }

  async function replaceSingleItem() {
    if (!canWrite || !selectedId) return;
    const prompt = itemPrompt.trim();
    if (!prompt) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/questionnaires/${encodeURIComponent(selectedId)}/items?${tenantQ()}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          items: [
            {
              itemId: "i1",
              sortOrder: 0,
              questionType: itemType,
              prompt,
              required: true,
              optionsJson: itemType === "SINGLE_CHOICE" || itemType === "MULTI_CHOICE" ? '["A","B"]' : "[]",
            },
          ],
        }),
      },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setItemPrompt("");
    setNotice(phrases.questionnairesItemsSavedNotice);
    await loadDetail(selectedId);
    await loadList();
  }

  async function lifecycle(action: "publish" | "close") {
    if (!canWrite || !selectedId) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/questionnaires/${encodeURIComponent(selectedId)}/${action}?${tenantQ()}`,
      { method: "POST" },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setNotice(
      action === "publish" ? phrases.questionnairesPublishedNotice : phrases.questionnairesClosedNotice,
    );
    await loadDetail(selectedId);
    await loadList();
  }

  async function addAssignment() {
    if (!canWrite || !selectedId) return;
    const id = assigneeId.trim();
    if (!id) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/questionnaires/${encodeURIComponent(selectedId)}/assignments?${tenantQ()}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ assigneeKind, assigneeId: id }),
      },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setAssigneeId("");
    setNotice(phrases.questionnairesAssignmentAddedNotice);
    await loadDetail(selectedId);
  }

  async function removeAssignment(assignmentId: string) {
    if (!canWrite || !selectedId || !assignmentId) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/questionnaires/${encodeURIComponent(selectedId)}/assignments/${encodeURIComponent(assignmentId)}?${tenantQ()}`,
      { method: "DELETE" },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setNotice(phrases.questionnairesAssignmentRemovedNotice);
    await loadDetail(selectedId);
  }

  function downloadCsv() {
    if (!selectedId || !tenantId.trim()) return;
    window.open(
      `/api/platform/questionnaires/${encodeURIComponent(selectedId)}/export.csv?${tenantQ()}`,
      "_blank",
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <p role="note" className="max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
        {phrases.questionnairesStubNote}
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.questionnairesTenantLabel}
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <button
          type="button"
          className="rounded-md border border-border bg-surface px-3 py-1 text-sm"
          onClick={() => void loadList()}
        >
          {phrases.questionnairesReloadAction}
        </button>
      </div>
      {status === "error" ? (
        <p className="text-sm text-danger">{phrases.loadFailedStatus.replace("{status}", String(errorStatus))}</p>
      ) : null}
      {notice ? <p className="text-sm text-muted">{notice}</p> : null}

      {canWrite ? (
        <div className="flex flex-wrap items-end gap-2 rounded-md border border-border p-3">
          <label className="flex flex-col gap-1 text-sm">
            {phrases.questionnairesTitleLabel}
            <input
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            {phrases.questionnairesModeLabel}
            <select
              value={newMode}
              onChange={(e) => setNewMode(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="AUTH_END_USER">AUTH_END_USER</option>
              <option value="ANON_LINK">ANON_LINK</option>
            </select>
          </label>
          <button
            type="button"
            disabled={busy || !newTitle.trim() || !tenantId.trim()}
            className="rounded-md bg-foreground px-3 py-1 text-sm text-background disabled:opacity-50"
            onClick={() => void createQuestionnaire()}
          >
            {phrases.questionnairesCreateAction}
          </button>
        </div>
      ) : (
        <p className="text-sm text-muted">{phrases.questionnairesWriteRequired}</p>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <h2 className="mb-2 text-sm font-medium">{phrases.questionnairesListHeading}</h2>
          {rows.length === 0 ? (
            <p className="text-sm text-muted">{phrases.emptyList}</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {rows.map((row) => (
                <li key={row.questionnaireId}>
                  <button
                    type="button"
                    className={`w-full rounded-md border px-2 py-1 text-left text-sm ${
                      selectedId === row.questionnaireId
                        ? "border-foreground bg-surface"
                        : "border-border bg-background"
                    }`}
                    onClick={() => setSelectedId(row.questionnaireId ?? "")}
                  >
                    <span className="font-medium">{row.title}</span>
                    <span className="ml-2 font-mono text-xs text-muted">
                      {row.status} · r{row.revision} · {row.collectMode}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <h2 className="mb-2 text-sm font-medium">{phrases.questionnairesDetailHeading}</h2>
          {!detail ? (
            <p className="text-sm text-muted">{phrases.questionnairesSelectHint}</p>
          ) : (
            <div className="flex flex-col gap-3">
              <p className="font-mono text-xs text-muted">{detail.questionnaireId}</p>
              <p className="text-sm">
                {detail.title} — {detail.status} (r{detail.revision})
              </p>
              <ul className="text-sm text-muted">
                {(detail.items ?? []).map((it) => (
                  <li key={it.itemId}>
                    [{it.questionType}] {it.prompt}
                  </li>
                ))}
              </ul>
              {canWrite && detail.status === "DRAFT" ? (
                <div className="flex flex-wrap items-end gap-2">
                  <label className="flex flex-col gap-1 text-sm">
                    {phrases.questionnairesItemPromptLabel}
                    <input
                      value={itemPrompt}
                      onChange={(e) => setItemPrompt(e.target.value)}
                      className="rounded-md border border-border bg-background px-2 py-1 text-sm"
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    {phrases.questionnairesItemTypeLabel}
                    <select
                      value={itemType}
                      onChange={(e) => setItemType(e.target.value)}
                      className="rounded-md border border-border bg-background px-2 py-1 text-sm"
                    >
                      <option value="TEXT_SHORT">TEXT_SHORT</option>
                      <option value="TEXT_LONG">TEXT_LONG</option>
                      <option value="SINGLE_CHOICE">SINGLE_CHOICE</option>
                      <option value="MULTI_CHOICE">MULTI_CHOICE</option>
                      <option value="RATING">RATING</option>
                    </select>
                  </label>
                  <button
                    type="button"
                    disabled={busy || !itemPrompt.trim()}
                    className="rounded-md border border-border px-3 py-1 text-sm disabled:opacity-50"
                    onClick={() => void replaceSingleItem()}
                  >
                    {phrases.questionnairesSaveItemsAction}
                  </button>
                </div>
              ) : null}
              <div className="flex flex-wrap gap-2">
                {canWrite && detail.status === "DRAFT" ? (
                  <button
                    type="button"
                    disabled={busy}
                    className="rounded-md bg-foreground px-3 py-1 text-sm text-background disabled:opacity-50"
                    onClick={() => void lifecycle("publish")}
                  >
                    {phrases.questionnairesPublishAction}
                  </button>
                ) : null}
                {canWrite && detail.status === "PUBLISHED" ? (
                  <button
                    type="button"
                    disabled={busy}
                    className="rounded-md border border-border px-3 py-1 text-sm disabled:opacity-50"
                    onClick={() => void lifecycle("close")}
                  >
                    {phrases.questionnairesCloseAction}
                  </button>
                ) : null}
                <button
                  type="button"
                  className="rounded-md border border-border px-3 py-1 text-sm"
                  onClick={downloadCsv}
                >
                  {phrases.questionnairesExportCsvAction}
                </button>
              </div>
              {detail.collectMode === "AUTH_END_USER" ? (
                <div className="flex flex-col gap-2 rounded-md border border-border p-2">
                  <h3 className="text-sm font-medium">{phrases.questionnairesAssignmentsHeading}</h3>
                  <p className="text-sm text-muted">{phrases.questionnairesAssignmentsHint}</p>
                  {assignments.length === 0 ? (
                    <p className="text-sm text-muted">{phrases.questionnairesAssignmentsEmpty}</p>
                  ) : (
                    <ul className="flex flex-col gap-1 text-sm">
                      {assignments.map((row) => (
                        <li key={row.assignmentId} className="flex flex-wrap items-center justify-between gap-2">
                          <span>
                            {row.assigneeKind === "ORGANIZATION"
                              ? phrases.questionnairesAssignmentKindOrganization
                              : phrases.questionnairesAssignmentKindSubject}
                            <span className="ml-2 font-mono text-xs">{row.assigneeId}</span>
                          </span>
                          {canWrite ? (
                            <button
                              type="button"
                              disabled={busy}
                              className="rounded-md border border-border px-2 py-0.5 text-xs disabled:opacity-50"
                              onClick={() => void removeAssignment(row.assignmentId ?? "")}
                            >
                              {phrases.questionnairesRemoveAssignmentAction}
                            </button>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  )}
                  {canWrite ? (
                    <div className="flex flex-wrap items-end gap-2">
                      <label className="flex flex-col gap-1 text-sm">
                        {phrases.questionnairesAssigneeKindLabel}
                        <select
                          value={assigneeKind}
                          onChange={(e) => { setAssigneeKind(e.target.value); setAssigneeId(""); }}
                          className="rounded-md border border-border bg-background px-2 py-1 text-sm"
                        >
                          <option value="SUBJECT">{phrases.questionnairesAssigneeKindSubject}</option>
                          <option value="ORGANIZATION">{phrases.questionnairesAssigneeKindOrganization}</option>
                        </select>
                      </label>
                      {assigneeKind === "ORGANIZATION" && organizations.length > 0 ? (
                        <label className="flex flex-col gap-1 text-sm">
                          {phrases.questionnairesOrganizationIdLabel}
                          <select
                            value={assigneeId}
                            onChange={(e) => setAssigneeId(e.target.value)}
                            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
                          >
                            <option value="">{phrases.questionnairesOrganizationIdLabel}</option>
                            {organizations.map((org) => (
                              <option key={org.organizationId} value={org.organizationId}>
                                {org.organizationName || org.organizationId}
                              </option>
                            ))}
                          </select>
                        </label>
                      ) : (
                        <label className="flex flex-col gap-1 text-sm">
                          {assigneeKind === "ORGANIZATION"
                            ? phrases.questionnairesOrganizationIdLabel
                            : phrases.questionnairesSubjectIdLabel}
                          <input
                            value={assigneeId}
                            onChange={(e) => setAssigneeId(e.target.value)}
                            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
                            autoComplete="off"
                          />
                        </label>
                      )}
                      <button
                        type="button"
                        disabled={busy || !assigneeId.trim()}
                        className="rounded-md border border-border px-3 py-1 text-sm disabled:opacity-50"
                        onClick={() => void addAssignment()}
                      >
                        {phrases.questionnairesAddAssignmentAction}
                      </button>
                    </div>
                  ) : null}
                </div>
              ) : (
                <p className="text-sm text-muted">{phrases.questionnairesAssignmentsHint}</p>
              )}
              {stats ? (
                <div className="rounded-md border border-border p-2 text-sm">
                  <p>
                    {phrases.questionnairesResponseCountLabel}: {stats.responseCount ?? 0}
                  </p>
                  <ul className="mt-1 text-muted">
                    {(stats.items ?? []).map((it) => (
                      <li key={it.itemId}>
                        {it.prompt}:{" "}
                        {Object.entries(it.counts ?? {})
                          .map(([k, v]) => `${k}=${v}`)
                          .join(", ") || "—"}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
