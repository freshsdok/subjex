"use client";

import { useCallback, useEffect, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { OPERATOR_PERMISSION_CATALOG } from "@/lib/operator-permission-catalog";

type EntryRow = {
  id: string;
  kind: string;
  href?: string | null;
  route?: string | null;
  permission?: string | null;
  anyOf?: string[] | null;
};

type ManifestBody = {
  version: number;
  client: string;
  defaults?: { lockedMenuEffect?: string; missingButtonEffect?: string };
  entries: EntryRow[];
};

type DraftReply = {
  revision?: number;
  draftState?: string;
  updatedAt?: string;
  manifest?: ManifestBody;
};

type Props = {
  phrases: PhraseBook;
  canWrite: boolean;
  canPromote: boolean;
};

/**
 * UI permission Manifest draft editor (PC-6a/6b) — UI 权限 Manifest 草稿编辑器。
 * <p>
 * Loads classpath baseline or saved draft; permission fields are catalog selects only.
 * Save PUTs JSON draft; promote POSTs to live overlay (PROMOTED > classpath). Never edits role_permission.
 * 加载基线或草稿；权限仅目录下拉。保存写草稿；晋升写线上叠层；绝不改角色授权。
 */
export function UiAuthzEditor({ phrases, canWrite, canPromote }: Props) {
  const [manifest, setManifest] = useState<ManifestBody | null>(null);
  const [revision, setRevision] = useState<number | null>(null);
  const [status, setStatus] = useState<"loading" | "ok" | "error">("loading");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [promoting, setPromoting] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);

  const load = useCallback(async () => {
    setStatus("loading");
    setNotice(null);
    const draftReply = await fetch("/api/platform/ui-permissions/draft?client=console", {
      cache: "no-store",
    }).catch(() => null);
    if (draftReply?.ok) {
      const body = (await draftReply.json()) as DraftReply;
      if (body.manifest) {
        setManifest(body.manifest);
        setRevision(body.revision ?? null);
        setStatus("ok");
        return;
      }
    }
    const baselineReply = await fetch("/api/platform/ui-permissions/draft/baseline?client=console", {
      cache: "no-store",
    }).catch(() => null);
    if (!baselineReply?.ok) {
      setErrorStatus(baselineReply?.status ?? 0);
      setStatus("error");
      return;
    }
    const baseline = (await baselineReply.json()) as ManifestBody;
    setManifest(baseline);
    setRevision(null);
    setStatus("ok");
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function save() {
    if (!canWrite || !manifest) return;
    setSaving(true);
    setNotice(null);
    const reply = await fetch("/api/platform/ui-permissions/draft?client=console", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(manifest),
    }).catch(() => null);
    setSaving(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setNotice(fillPhrase(phrases.uiAuthzSaveFailed, { status: String(reply?.status ?? 0) }));
      return;
    }
    const body = (await reply.json()) as DraftReply;
    if (body.manifest) setManifest(body.manifest);
    setRevision(body.revision ?? null);
    setNotice(phrases.uiAuthzSavedNotice);
  }

  async function promote() {
    if (!canPromote) return;
    setPromoting(true);
    setNotice(null);
    const reply = await fetch("/api/platform/ui-permissions/draft/promote?client=console", {
      method: "POST",
    }).catch(() => null);
    setPromoting(false);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setNotice(fillPhrase(phrases.uiAuthzPromoteFailed, { status: String(reply?.status ?? 0) }));
      return;
    }
    setRevision(null);
    setNotice(phrases.uiAuthzPromotedNotice);
    await load();
  }

  function exportYaml() {
    window.open("/api/platform/ui-permissions/draft/export?client=console", "_blank");
  }

  function updateSelected(patch: Partial<EntryRow>) {
    if (!manifest || !canWrite) return;
    const entries = [...manifest.entries];
    const current = entries[selectedIndex];
    if (!current) return;
    entries[selectedIndex] = { ...current, ...patch };
    setManifest({ ...manifest, entries });
  }

  const selected = manifest?.entries[selectedIndex];

  return (
    <div className="space-y-4">
      {!canWrite ? (
        <p role="status" className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.uiAuthzWriteRequired}
        </p>
      ) : null}
      {canWrite && !canPromote ? (
        <p role="status" className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.uiAuthzPromoteRequired}
        </p>
      ) : null}
      <p className="text-sm text-muted">{phrases.uiAuthzPromoteHint}</p>
      {status === "loading" ? <p className="text-sm text-muted">{phrases.loading}</p> : null}
      {status === "error" ? (
        <p className="text-sm text-danger">
          {fillPhrase(phrases.loadFailedStatus, { status: String(errorStatus) })}
        </p>
      ) : null}
      {notice ? <p className="text-sm text-muted">{notice}</p> : null}
      {manifest ? (
        <>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span>
              {phrases.uiAuthzRevisionLabel}:{" "}
              <strong>{revision == null ? phrases.uiAuthzBaselineLabel : revision}</strong>
            </span>
            <button
              type="button"
              disabled={!canWrite || saving}
              onClick={() => void save()}
              className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50"
            >
              {saving ? phrases.uiAuthzSaving : phrases.uiAuthzSaveAction}
            </button>
            <button
              type="button"
              disabled={!canPromote || promoting || revision == null}
              onClick={() => void promote()}
              className="rounded-md border border-border px-3 py-1.5 hover:bg-surface disabled:opacity-50"
            >
              {promoting ? phrases.uiAuthzPromoting : phrases.uiAuthzPromoteAction}
            </button>
            <button
              type="button"
              onClick={() => exportYaml()}
              className="rounded-md border border-border px-3 py-1.5 hover:bg-surface"
            >
              {phrases.uiAuthzExportAction}
            </button>
            <button
              type="button"
              onClick={() => void load()}
              className="rounded-md border border-border px-3 py-1.5 hover:bg-surface"
            >
              {phrases.uiAuthzReloadAction}
            </button>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <ul className="max-h-[28rem] overflow-auto rounded-md border border-border text-sm">
              {manifest.entries.map((entry, index) => (
                <li key={`${entry.id}-${index}`}>
                  <button
                    type="button"
                    onClick={() => setSelectedIndex(index)}
                    className={`flex w-full items-center justify-between px-3 py-2 text-left hover:bg-surface ${
                      index === selectedIndex ? "bg-surface font-medium" : ""
                    }`}
                  >
                    <span className="font-mono text-xs">{entry.id}</span>
                    <span className="text-muted">{entry.kind}</span>
                  </button>
                </li>
              ))}
            </ul>
            {selected ? (
              <div className="space-y-3 rounded-md border border-border p-4 text-sm">
                <div>
                  <label className="mb-1 block text-muted">{phrases.uiAuthzIdLabel}</label>
                  <input
                    className="w-full rounded-md border border-border bg-background px-2 py-1 font-mono"
                    value={selected.id}
                    disabled={!canWrite}
                    onChange={(e) => updateSelected({ id: e.target.value })}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-muted">{phrases.uiAuthzKindLabel}</label>
                  <select
                    className="w-full rounded-md border border-border bg-background px-2 py-1"
                    value={selected.kind}
                    disabled={!canWrite}
                    onChange={(e) => updateSelected({ kind: e.target.value })}
                  >
                    <option value="menu">menu</option>
                    <option value="page">page</option>
                    <option value="button">button</option>
                  </select>
                </div>
                {selected.kind === "menu" ? (
                  <div>
                    <label className="mb-1 block text-muted">{phrases.uiAuthzHrefLabel}</label>
                    <input
                      className="w-full rounded-md border border-border bg-background px-2 py-1 font-mono"
                      value={selected.href ?? ""}
                      disabled={!canWrite}
                      onChange={(e) => updateSelected({ href: e.target.value || null })}
                    />
                  </div>
                ) : null}
                {selected.kind === "page" ? (
                  <div>
                    <label className="mb-1 block text-muted">{phrases.uiAuthzRouteLabel}</label>
                    <input
                      className="w-full rounded-md border border-border bg-background px-2 py-1 font-mono"
                      value={selected.route ?? ""}
                      disabled={!canWrite}
                      onChange={(e) => updateSelected({ route: e.target.value || null })}
                    />
                  </div>
                ) : null}
                <div>
                  <label className="mb-1 block text-muted">{phrases.uiAuthzPermissionLabel}</label>
                  <select
                    className="w-full rounded-md border border-border bg-background px-2 py-1"
                    value={selected.permission ?? ""}
                    disabled={!canWrite}
                    onChange={(e) =>
                      updateSelected({ permission: e.target.value ? e.target.value : null })
                    }
                  >
                    <option value="">{phrases.uiAuthzPermissionNone}</option>
                    {OPERATOR_PERMISSION_CATALOG.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="mb-1 block text-muted">{phrases.uiAuthzAnyOfLabel}</label>
                  <select
                    multiple
                    className="h-40 w-full rounded-md border border-border bg-background px-2 py-1"
                    value={selected.anyOf ?? []}
                    disabled={!canWrite}
                    onChange={(e) => {
                      const values = Array.from(e.target.selectedOptions).map((o) => o.value);
                      updateSelected({ anyOf: values.length ? values : null });
                    }}
                  >
                    {OPERATOR_PERMISSION_CATALOG.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                  <p className="mt-1 text-xs text-muted">{phrases.uiAuthzAnyOfHint}</p>
                </div>
              </div>
            ) : null}
          </div>
        </>
      ) : null}
    </div>
  );
}
