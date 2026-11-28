"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type ObjectRow = {
  objectId?: string;
  objectKey?: string;
  ownerSubjectId?: string;
  createdAt?: string;
};

type GrantRow = {
  grantId?: string;
  objectId?: string;
  objectKey?: string;
  tenantId?: string;
  granteeKind?: string;
  granteeId?: string;
  expiresAt?: string | null;
  createdAt?: string;
  createdBySubjectId?: string;
  effective?: boolean;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Thin operator object-grant console — list, grant, revoke (cut 2).
 * 薄操作员对象授权台：列出、授予、撤销。手工填写被授权人编号，不是选人产品。
 */
export function ObjectsConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [rows, setRows] = useState<ObjectRow[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [grants, setGrants] = useState<GrantRow[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [granteeKind, setGranteeKind] = useState("SUBJECT");
  const [granteeId, setGranteeId] = useState("");
  const [expiresAt, setExpiresAt] = useState("");

  const tenantQ = () => `tenantId=${encodeURIComponent(tenantId.trim())}`;

  const loadList = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setRows([]);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    setErrorStatus(0);
    const reply = await fetch(`/api/platform/objects?${tenantQ()}`, { cache: "no-store" }).catch(
      () => null,
    );
    if (!reply?.ok) {
      setRows([]);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as { objects?: ObjectRow[] };
    setRows(body.objects ?? []);
    setStatus("ok");
  }, [tenantId]);

  const loadGrants = useCallback(
    async (objectId: string) => {
      const tid = tenantId.trim();
      if (!tid || !objectId) {
        setGrants([]);
        return;
      }
      const reply = await fetch(
        `/api/platform/objects/${encodeURIComponent(objectId)}/grants?${tenantQ()}`,
        { cache: "no-store" },
      ).catch(() => null);
      if (!reply?.ok) {
        setGrants([]);
        setErrorStatus(reply?.status ?? 0);
        return;
      }
      const body = (await reply.json()) as { grants?: GrantRow[] };
      setGrants(body.grants ?? []);
    },
    [tenantId],
  );

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (!selectedId) {
      setGrants([]);
      return;
    }
    void loadGrants(selectedId);
  }, [selectedId, loadGrants]);

  async function onSearch(event: FormEvent) {
    event.preventDefault();
    setNotice(null);
    setSelectedId("");
    await loadList();
  }

  async function onGrant(event: FormEvent) {
    event.preventDefault();
    if (!canWrite || !selectedId) return;
    const id = granteeId.trim();
    if (!id) return;
    setBusy(true);
    setNotice(null);
    const body: { granteeKind: string; granteeId: string; expiresAt: string | null } = {
      granteeKind,
      granteeId: id,
      expiresAt: expiresAt.trim() ? new Date(expiresAt.trim()).toISOString() : null,
    };
    const reply = await fetch(
      `/api/platform/objects/${encodeURIComponent(selectedId)}/grants?${tenantQ()}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setNotice(phrases.objectsGrantFailed);
      setErrorStatus(reply?.status ?? 0);
      return;
    }
    setGranteeId("");
    setExpiresAt("");
    await loadGrants(selectedId);
  }

  async function onRevoke(grantId: string) {
    if (!canWrite || !selectedId || !grantId) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/objects/${encodeURIComponent(selectedId)}/grants/${encodeURIComponent(grantId)}?${tenantQ()}`,
      { method: "DELETE" },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setNotice(phrases.objectsRevokeFailed);
      setErrorStatus(reply?.status ?? 0);
      return;
    }
    await loadGrants(selectedId);
  }

  return (
    <div className="flex flex-col gap-4">
      <form
        onSubmit={onSearch}
        className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
      >
        <label className="flex min-w-[10rem] flex-col gap-1">
          <span className="text-xs text-muted">{phrases.objectsTenantLabel}</span>
          <input
            type="text"
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
            placeholder={phrases.objectsTenantPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1.5"
          />
        </label>
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.objectsSearchAction}
        </button>
      </form>
      {!tenantId.trim() ? (
        <p className="text-sm text-muted">{phrases.objectsMissingTenant}</p>
      ) : status === "error" ? (
        <p className="text-sm text-muted">
          {errorStatus ? `HTTP ${errorStatus}` : phrases.objectsGrantFailed}
        </p>
      ) : status === "loading" ? (
        <p className="text-sm text-muted">…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted">{phrases.objectsEmpty}</p>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
            <thead className="bg-background text-left text-muted">
              <tr>
                <th className="px-3 py-2 font-medium">{phrases.objectsObjectKeyLabel}</th>
                <th className="px-3 py-2 font-medium">{phrases.objectsOwnerLabel}</th>
                <th className="px-3 py-2 font-medium">{phrases.objectsCreatedAtLabel}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const id = row.objectId ?? "";
                const selected = id === selectedId;
                return (
                  <tr
                    key={id || row.objectKey}
                    className={`cursor-pointer border-t border-border ${selected ? "bg-background" : ""}`}
                    onClick={() => setSelectedId(id)}
                  >
                    <td className="px-3 py-2 font-mono text-xs">{row.objectKey}</td>
                    <td className="px-3 py-2 font-mono text-xs">{row.ownerSubjectId}</td>
                    <td className="whitespace-nowrap px-3 py-2">{row.createdAt ?? ""}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="rounded-lg border border-border bg-surface p-3 text-sm">
            {!selectedId ? (
              <p className="text-muted">{phrases.objectsSelectHint}</p>
            ) : (
              <>
                <h2 className="mb-2 font-medium">{phrases.objectsGrantsTitle}</h2>
                {grants.length === 0 ? (
                  <p className="mb-3 text-muted">{phrases.objectsGrantsEmpty}</p>
                ) : (
                  <ul className="mb-3 flex flex-col gap-2">
                    {grants.map((grant) => (
                      <li
                        key={grant.grantId}
                        className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-2"
                      >
                        <span>
                          {grant.granteeKind === "ORGANIZATION"
                            ? phrases.objectsGranteeKindOrganization
                            : phrases.objectsGranteeKindSubject}
                          <span className="ml-1 font-mono text-xs">{grant.granteeId}</span>
                          {grant.expiresAt ? (
                            <span className="ml-1 text-xs text-muted">{grant.expiresAt}</span>
                          ) : null}
                          <span className="ml-1 text-xs">
                            {grant.effective ? phrases.objectsEffectiveYes : phrases.objectsEffectiveNo}
                          </span>
                        </span>
                        {canWrite && grant.grantId ? (
                          <button
                            type="button"
                            disabled={busy}
                            className="rounded-md border border-border px-2 py-1"
                            onClick={() => void onRevoke(grant.grantId as string)}
                          >
                            {phrases.objectsRevokeAction}
                          </button>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                )}
                {canWrite ? (
                  <form onSubmit={onGrant} className="flex flex-col gap-2">
                    <label className="flex flex-col gap-1">
                      <span className="text-xs text-muted">{phrases.objectsGranteeKindLabel}</span>
                      <select
                        value={granteeKind}
                        onChange={(event) => setGranteeKind(event.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1.5"
                      >
                        <option value="SUBJECT">{phrases.objectsGranteeKindSubject}</option>
                        <option value="ORGANIZATION">{phrases.objectsGranteeKindOrganization}</option>
                      </select>
                    </label>
                    <label className="flex flex-col gap-1">
                      <span className="text-xs text-muted">{phrases.objectsGranteeIdLabel}</span>
                      <input
                        type="text"
                        value={granteeId}
                        onChange={(event) => setGranteeId(event.target.value)}
                        placeholder={phrases.objectsGranteeIdPlaceholder}
                        className="rounded-md border border-border bg-background px-2 py-1.5"
                      />
                    </label>
                    <label className="flex flex-col gap-1">
                      <span className="text-xs text-muted">{phrases.objectsExpiresAtLabel}</span>
                      <input
                        type="datetime-local"
                        value={expiresAt}
                        onChange={(event) => setExpiresAt(event.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1.5"
                      />
                    </label>
                    <button
                      type="submit"
                      disabled={busy || !granteeId.trim()}
                      className="rounded-md bg-accent px-3 py-1.5 text-white disabled:opacity-50"
                    >
                      {phrases.objectsGrantAction}
                    </button>
                  </form>
                ) : (
                  <p className="text-muted">{phrases.objectsNoWrite}</p>
                )}
                {notice ? <p className="mt-2 text-sm text-muted">{notice}</p> : null}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
