"use client";

import { useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type ScheduleDocument = {
  scheduleId?: string;
  name?: string;
  cronExpression?: string;
  timezone?: string;
  enabled?: boolean;
  actionType?: string;
  taskStepName?: string | null;
  workflowDefinitionKey?: string | null;
  workflowRecordId?: string | null;
  nextRunAt?: string | null;
  lastRunAt?: string | null;
  lastError?: string | null;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Thin operator schedule console — list, create, enable, and disable.
 * 薄操作员调度台：列出、创建、启用与停用。
 */
export function SchedulesConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [rows, setRows] = useState<ScheduleDocument[] | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [cronExpression, setCronExpression] = useState("0 0 9 * * *");
  const [action, setAction] = useState("ENQUEUE_TASK");
  const [taskStepName, setTaskStepName] = useState("");
  const [workflowDefinitionKey, setWorkflowDefinitionKey] = useState("");
  const [workflowRecordId, setWorkflowRecordId] = useState("");

  const tenantQ = () => `tenantId=${encodeURIComponent(tenantId.trim())}`;

  async function loadList() {
    const tid = tenantId.trim();
    if (!tid) {
      setRows(null);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    const reply = await fetch(`/api/platform/schedules?${tenantQ()}`, { cache: "no-store" }).catch(
      () => null,
    );
    if (!reply?.ok) {
      setRows(null);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setRows((await reply.json()) as ScheduleDocument[]);
    setStatus("ok");
  }

  async function onLookup(event: FormEvent) {
    event.preventDefault();
    setNotice(null);
    await loadList();
  }

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    if (!canWrite) return;
    const tid = tenantId.trim();
    if (!tid || !name.trim() || !cronExpression.trim()) return;
    setBusy(true);
    setNotice(null);
    const body: Record<string, unknown> = {
      name: name.trim(),
      cronExpression: cronExpression.trim(),
      enabled: true,
      action,
    };
    if (action === "ENQUEUE_TASK") {
      body.taskStepName = taskStepName.trim();
    } else {
      body.workflowDefinitionKey = workflowDefinitionKey.trim();
      body.workflowRecordId = workflowRecordId.trim();
    }
    const reply = await fetch(`/api/platform/schedules?${tenantQ()}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setNotice(phrases.schedulesCreateFailed);
      setErrorStatus(reply?.status ?? 0);
      return;
    }
    setNotice(phrases.schedulesCreated);
    setName("");
    setTaskStepName("");
    setWorkflowDefinitionKey("");
    setWorkflowRecordId("");
    await loadList();
  }

  async function toggle(scheduleId: string, enable: boolean) {
    if (!canWrite) return;
    setBusy(true);
    setNotice(null);
    const path = enable ? "enable" : "disable";
    const reply = await fetch(`/api/platform/schedules/${scheduleId}/${path}?${tenantQ()}`, {
      method: "POST",
    }).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setNotice(phrases.schedulesToggleFailed);
      setErrorStatus(reply?.status ?? 0);
      return;
    }
    setNotice(phrases.schedulesUpdated);
    await loadList();
  }

  return (
    <div className="space-y-6">
      <form className="flex flex-wrap items-end gap-3" onSubmit={onLookup}>
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.schedulesTenant}</span>
          <input
            className="rounded border border-border bg-surface px-3 py-2"
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
          />
        </label>
        <button className="rounded bg-foreground px-3 py-2 text-sm text-background" type="submit">
          {phrases.schedulesLookup}
        </button>
      </form>
      {!tenantId.trim() ? <p className="text-sm text-muted">{phrases.schedulesMissingTenant}</p> : null}
      {status === "error" ? (
        <p className="text-sm text-muted">
          {phrases.schedulesLoadFailed}
          {errorStatus ? ` (${errorStatus})` : ""}
        </p>
      ) : null}
      {notice ? <p className="text-sm">{notice}</p> : null}
      {!canWrite ? <p className="text-sm text-muted">{phrases.schedulesNoWrite}</p> : null}

      {canWrite ? (
        <form className="space-y-3 rounded-lg border border-border bg-surface p-4" onSubmit={onCreate}>
          <p className="text-sm text-muted">{phrases.schedulesCronHint}</p>
          <div className="flex flex-wrap gap-3">
            <label className="flex min-w-48 flex-col gap-1 text-sm">
              <span>{phrases.schedulesName}</span>
              <input
                className="rounded border border-border bg-background px-3 py-2"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <label className="flex min-w-48 flex-col gap-1 text-sm">
              <span>{phrases.schedulesCron}</span>
              <input
                className="rounded border border-border bg-background px-3 py-2 font-mono"
                value={cronExpression}
                onChange={(event) => setCronExpression(event.target.value)}
              />
            </label>
            <label className="flex min-w-48 flex-col gap-1 text-sm">
              <span>{phrases.schedulesAction}</span>
              <select
                className="rounded border border-border bg-background px-3 py-2"
                value={action}
                onChange={(event) => setAction(event.target.value)}
              >
                <option value="ENQUEUE_TASK">{phrases.schedulesActionTask}</option>
                <option value="START_WORKFLOW">{phrases.schedulesActionWorkflow}</option>
              </select>
            </label>
            {action === "ENQUEUE_TASK" ? (
              <label className="flex min-w-48 flex-col gap-1 text-sm">
                <span>{phrases.schedulesStepName}</span>
                <input
                  className="rounded border border-border bg-background px-3 py-2"
                  value={taskStepName}
                  onChange={(event) => setTaskStepName(event.target.value)}
                />
              </label>
            ) : (
              <>
                <label className="flex min-w-48 flex-col gap-1 text-sm">
                  <span>{phrases.schedulesWorkflowKey}</span>
                  <input
                    className="rounded border border-border bg-background px-3 py-2 font-mono"
                    value={workflowDefinitionKey}
                    onChange={(event) => setWorkflowDefinitionKey(event.target.value)}
                  />
                </label>
                <label className="flex min-w-48 flex-col gap-1 text-sm">
                  <span>{phrases.schedulesWorkflowRecord}</span>
                  <input
                    className="rounded border border-border bg-background px-3 py-2 font-mono"
                    value={workflowRecordId}
                    onChange={(event) => setWorkflowRecordId(event.target.value)}
                  />
                </label>
              </>
            )}
          </div>
          <button
            className="rounded bg-foreground px-3 py-2 text-sm text-background disabled:opacity-50"
            type="submit"
            disabled={busy}
          >
            {phrases.schedulesCreate}
          </button>
        </form>
      ) : null}

      {status === "ok" && rows && rows.length === 0 ? (
        <p className="text-sm text-muted">{phrases.schedulesEmpty}</p>
      ) : null}
      {rows && rows.length > 0 ? (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.schedulesName}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesCron}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesTimezone}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesAction}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesEnabled}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesNextRun}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesLastRun}</th>
              <th className="px-3 py-2 font-medium">{phrases.schedulesLastError}</th>
              {canWrite ? <th className="px-3 py-2 font-medium" /> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.scheduleId} className="border-t border-border">
                <td className="px-3 py-2">{row.name}</td>
                <td className="px-3 py-2 font-mono text-xs">{row.cronExpression}</td>
                <td className="px-3 py-2 font-mono text-xs">{row.timezone}</td>
                <td className="px-3 py-2">
                  {row.actionType === "START_WORKFLOW"
                    ? `${row.workflowDefinitionKey ?? ""} / ${row.workflowRecordId ?? ""}`
                    : row.taskStepName}
                </td>
                <td className="px-3 py-2">
                  {row.enabled ? phrases.schedulesEnabled : phrases.schedulesDisabled}
                </td>
                <td className="whitespace-nowrap px-3 py-2">{row.nextRunAt ?? ""}</td>
                <td className="whitespace-nowrap px-3 py-2">{row.lastRunAt ?? ""}</td>
                <td className="px-3 py-2 text-xs">{row.lastError ?? ""}</td>
                {canWrite ? (
                  <td className="px-3 py-2">
                    <button
                      className="rounded border border-border px-2 py-1 text-xs disabled:opacity-50"
                      type="button"
                      disabled={busy || !row.scheduleId}
                      onClick={() => toggle(row.scheduleId ?? "", !row.enabled)}
                    >
                      {row.enabled ? phrases.schedulesDisable : phrases.schedulesEnable}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </div>
  );
}
