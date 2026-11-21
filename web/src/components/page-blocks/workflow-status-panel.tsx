"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type HistoryRow = {
  historyId?: string;
  fromState?: string | null;
  actionName?: string;
  toState?: string;
  actorSubjectId?: string;
  noteText?: string | null;
  capabilityId?: string | null;
  createdAt?: string;
};

type PendingApproval = {
  approvalId?: string;
  requestedBySubjectId?: string;
  approvalState?: string;
  createdAt?: string;
};

type InstanceDoc = {
  instanceId?: string;
  tenantId?: string;
  definitionKey?: string;
  entityKey?: string;
  recordId?: string;
  currentState?: string;
  startedBySubjectId?: string;
  startedAt?: string;
  updatedAt?: string;
};

type LegalAction = {
  action?: string;
  toState?: string;
  requiredPermission?: string;
  consumeApproval?: boolean;
};

type StatusReply = {
  instance?: InstanceDoc;
  history?: HistoryRow[];
  pendingApproval?: PendingApproval | null;
  approvalCandidateSubjectIds?: string[];
  legalActions?: LegalAction[];
};

type DefinitionReply = {
  definitionKey?: string;
  entityKey?: string;
  initialState?: string;
  autoStartOnUpsert?: boolean;
};

type MeReply = { subjectId?: string; permissions?: string[] };

/**
 * WorkflowStatusPanel — WF-3 / WP4-2 console UI for the machine bound to this entityKey.
 * Resolves the definition by entity (not a hardcoded repair-ticket key). Unbound entities hide the panel.
 * WF-3 / WP4-2：按 entityKey 解析声明，不写死 repair-ticket；未绑定实体隐藏本面板。
 */
export function WorkflowStatusPanel({
  entityKey,
  recordId,
  phrases,
  tenantId,
}: {
  entityKey: string;
  recordId: string;
  phrases: PhraseBook;
  tenantId?: string;
}) {
  const [definition, setDefinition] = useState<DefinitionReply | null>(null);
  const [unbound, setUnbound] = useState(false);
  const [status, setStatus] = useState<StatusReply | null>(null);
  const [missing, setMissing] = useState(false);
  const [subjectId, setSubjectId] = useState<string | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const tenantHeaders = useCallback((): Record<string, string> => {
    const headers: Record<string, string> = { accept: "application/json" };
    if (tenantId && tenantId.trim() !== "") {
      headers["X-Tenant-Id"] = tenantId.trim();
    }
    return headers;
  }, [tenantId]);

  const definitionUrl = useMemo(() => {
    const q = new URLSearchParams({ entityKey });
    return `/api/platform/workflows/definitions/by-entity?${q.toString()}`;
  }, [entityKey]);

  const byRecordUrl = useMemo(() => {
    const q = new URLSearchParams({
      tenantId: tenantId?.trim() || "",
      entityKey,
      recordId,
    });
    return `/api/platform/workflows/by-record?${q.toString()}`;
  }, [tenantId, entityKey, recordId]);

  const loadMe = useCallback(async () => {
    const reply = await fetch("/api/platform/me", { cache: "no-store" }).catch(() => null);
    if (!reply || !reply.ok) return;
    const body = (await reply.json()) as MeReply;
    setSubjectId(typeof body.subjectId === "string" ? body.subjectId : null);
    setPermissions(Array.isArray(body.permissions) ? body.permissions.filter((p) => typeof p === "string") : []);
  }, []);

  const loadDefinition = useCallback(async () => {
    const reply = await fetch(definitionUrl, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply) {
      setError(fillPhrase(phrases.workflowLoadFailed, { status: 0 }));
      return;
    }
    if (reply.status === 404) {
      setUnbound(true);
      setDefinition(null);
      return;
    }
    if (!reply.ok) {
      setError(fillPhrase(phrases.workflowLoadFailed, { status: reply.status }));
      return;
    }
    const body = (await reply.json()) as DefinitionReply;
    setUnbound(false);
    setDefinition(body);
  }, [definitionUrl, tenantHeaders, phrases.workflowLoadFailed]);

  const loadStatus = useCallback(async () => {
    setError(null);
    if (!tenantId || tenantId.trim() === "") {
      setMissing(true);
      setStatus(null);
      return;
    }
    const reply = await fetch(byRecordUrl, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply) {
      setError(fillPhrase(phrases.workflowLoadFailed, { status: 0 }));
      return;
    }
    if (reply.status === 404) {
      setMissing(true);
      setStatus(null);
      return;
    }
    if (!reply.ok) {
      setError(fillPhrase(phrases.workflowLoadFailed, { status: reply.status }));
      return;
    }
    const body = (await reply.json()) as StatusReply;
    setMissing(false);
    setStatus(body);
  }, [byRecordUrl, tenantHeaders, phrases.workflowLoadFailed, tenantId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      // Yield so loader setState is not synchronous in this effect.
      // 先让出事件循环，避免在 effect 内同步 setState。
      await Promise.resolve();
      if (!cancelled) {
        await loadMe();
        await loadDefinition();
        await loadStatus();
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadMe, loadDefinition, loadStatus]);

  async function readReason(reply: Response): Promise<string> {
    try {
      const body = (await reply.json()) as { reason?: unknown };
      return typeof body.reason === "string" && body.reason.trim() !== ""
        ? ` — ${body.reason.trim()}`
        : "";
    } catch {
      return "";
    }
  }

  async function startWorkflow() {
    const definitionKey = definition?.definitionKey?.trim();
    if (!tenantId?.trim() || !definitionKey) return;
    setBusy(true);
    setError(null);
    try {
      const reply = await fetch(`/api/platform/workflows/${encodeURIComponent(definitionKey)}/instances`, {
        method: "POST",
        headers: { ...tenantHeaders(), "content-type": "application/json" },
        body: JSON.stringify({
          tenantId: tenantId.trim(),
          entityKey,
          recordId,
        }),
      }).catch(() => null);
      if (!reply || !reply.ok) {
        const reason = reply ? await readReason(reply) : "";
        setError(
          fillPhrase(phrases.workflowActionFailed, {
            status: reply?.status ?? 0,
            reason,
          }),
        );
        return;
      }
      await loadStatus();
    } finally {
      setBusy(false);
    }
  }

  async function runAction(action: string) {
    const instanceId = status?.instance?.instanceId;
    if (!instanceId) return;
    setBusy(true);
    setError(null);
    try {
      const reply = await fetch(
        `/api/platform/workflows/instances/${encodeURIComponent(instanceId)}/transitions`,
        {
          method: "POST",
          headers: { ...tenantHeaders(), "content-type": "application/json" },
          body: JSON.stringify({ action, note: note.trim() || undefined }),
        },
      ).catch(() => null);
      if (!reply || !reply.ok) {
        const reason = reply ? await readReason(reply) : "";
        setError(
          fillPhrase(phrases.workflowActionFailed, {
            status: reply?.status ?? 0,
            reason,
          }),
        );
        return;
      }
      setNote("");
      await loadStatus();
    } finally {
      setBusy(false);
    }
  }

  const state = status?.instance?.currentState ?? null;
  const pendingRequester = status?.pendingApproval?.requestedBySubjectId ?? null;
  const isSelfRequester = Boolean(subjectId && pendingRequester && subjectId === pendingRequester);
  const canWrite = permissions.includes("workflow.write");
  const canApprove = permissions.includes("workflow.approve");
  const canRead = permissions.includes("workflow.read") || canWrite || canApprove;

  if (!canRead && permissions.length > 0) {
    return null;
  }
  // No machine is bound to this entity — hide the panel (do not offer a fake Start).
  // 该实体未绑定机器时隐藏面板，避免误显示「启动」。
  if (unbound) {
    return null;
  }

  const actions: { action: string; label: string; enabled: boolean }[] = [];
  for (const legal of status?.legalActions ?? []) {
    const action = typeof legal.action === "string" ? legal.action.trim() : "";
    if (action === "") continue;
    const required = typeof legal.requiredPermission === "string" ? legal.requiredPermission : "";
    const permitted = required !== "" ? permissions.includes(required) : canWrite;
    const enabled = permitted && !busy && !(Boolean(legal.consumeApproval) && isSelfRequester);
    actions.push({
      action,
      label: actionLabel(action, phrases),
      enabled,
    });
  }

  return (
    <div className="mt-6 max-w-2xl rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-semibold text-foreground">{phrases.workflowPanelTitle}</h2>
      <p className="mt-1 text-xs text-muted">{phrases.workflowPanelHint}</p>

      {error ? <p className="mt-3 text-sm text-danger">{error}</p> : null}

      {missing || !status?.instance ? (
        <div className="mt-3 space-y-2">
          <p className="text-sm text-muted">{phrases.workflowNoInstance}</p>
          {canWrite && definition?.definitionKey ? (
            <button
              type="button"
              className="rounded bg-accent px-3 py-1.5 text-sm text-white disabled:opacity-50"
              disabled={busy || !tenantId?.trim()}
              onClick={() => void startWorkflow()}
            >
              {phrases.workflowStartAction}
            </button>
          ) : null}
        </div>
      ) : (
        <div className="mt-3 space-y-3">
          <p className="text-sm">
            <span className="text-muted">{phrases.workflowStateLabel}: </span>
            <code className="rounded bg-background px-1.5 py-0.5 text-xs">{state}</code>
          </p>
          {pendingRequester ? (
            <p className="text-xs text-muted">
              {fillPhrase(phrases.workflowPendingRequester, { subjectId: pendingRequester })}
            </p>
          ) : null}
          {status?.pendingApproval ? (
            <p className="text-xs text-muted">
              {(status?.approvalCandidateSubjectIds?.length ?? 0) > 0
                ? fillPhrase(phrases.workflowApprovalCandidates, {
                    subjects: (status?.approvalCandidateSubjectIds ?? []).join(", "),
                  })
                : phrases.workflowApprovalCandidatesEmpty}
            </p>
          ) : null}
          {isSelfRequester ? (
            <p className="text-xs text-muted">{phrases.workflowSelfApproveHint}</p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <input
              type="text"
              className="min-w-[12rem] flex-1 rounded border border-border bg-background px-2 py-1 text-sm"
              placeholder={phrases.workflowNotePlaceholder}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              disabled={busy}
            />
            {actions.map((a) => (
              <button
                key={a.action}
                type="button"
                className="rounded border border-border bg-background px-3 py-1.5 text-sm hover:bg-surface disabled:opacity-50"
                disabled={!a.enabled}
                onClick={() => void runAction(a.action)}
              >
                {a.label}
              </button>
            ))}
            <button
              type="button"
              className="rounded border border-border px-3 py-1.5 text-sm disabled:opacity-50"
              disabled={busy}
              onClick={() => void loadStatus()}
            >
              {phrases.workflowRefreshAction}
            </button>
          </div>

          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted">
              {phrases.workflowHistoryTitle}
            </h3>
            {(status.history ?? []).length === 0 ? (
              <p className="mt-1 text-xs text-muted">{phrases.workflowHistoryEmpty}</p>
            ) : (
              <ul className="mt-2 space-y-1 font-mono text-xs">
                {(status.history ?? []).map((h) => (
                  <li key={h.historyId ?? `${h.actionName}-${h.createdAt}`}>
                    <span className="text-muted">{h.createdAt ?? ""}</span>{" "}
                    {h.fromState ?? "—"} → {h.toState}{" "}
                    <strong>{h.actionName}</strong> ({h.actorSubjectId})
                    {h.noteText ? ` — ${h.noteText}` : ""}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/** Keep historic repair-ticket phrases; unknown wires show the action token. 已知线名沿用原措辞，其它显示令牌。 */
function actionLabel(action: string, phrases: PhraseBook): string {
  switch (action) {
    case "start_work":
      return phrases.workflowActionStartWork;
    case "request_approval":
      return phrases.workflowActionRequestApproval;
    case "approve":
      return phrases.workflowActionApprove;
    case "reject":
      return phrases.workflowActionReject;
    case "close":
      return phrases.workflowActionClose;
    default:
      return action;
  }
}
