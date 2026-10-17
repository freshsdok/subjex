"use client";

import { useCallback, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieWrite } from "@/lib/org-console";

type GroupRow = {
  groupId?: string;
  name?: string;
  description?: string | null;
  realm?: string;
  updatedAt?: string;
};

type MemberRow = {
  memberSubjectId?: string;
  memberRealm?: string;
  addedAt?: string;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
};

/**
 * InboxGroupsConsole — IM-A7 group CRUD + member add/remove.
 * 站内信组控制台：组 CRUD 与成员维护。
 */
export function InboxGroupsConsole({ phrases, initialTenantId }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");
  const [editDesc, setEditDesc] = useState("");
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [members, setMembers] = useState<MemberRow[]>([]);
  const [membersStatus, setMembersStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [membersError, setMembersError] = useState(0);
  const [memberId, setMemberId] = useState("");
  const [addingMember, setAddingMember] = useState(false);

  const tenantHeaders = useCallback((): Record<string, string> => {
    const headers: Record<string, string> = { accept: "application/json" };
    const tid = tenantId.trim();
    if (tid) headers["X-Tenant-Id"] = tid;
    return headers;
  }, [tenantId]);

  const loadMembers = useCallback(
    async (groupId: string) => {
      const tid = tenantId.trim();
      if (!tid || !groupId) return;
      setMembersStatus("loading");
      const reply = await fetch(
        `/api/platform/inbox/groups/${encodeURIComponent(groupId)}/members?tenantId=${encodeURIComponent(tid)}`,
        { cache: "no-store", headers: tenantHeaders() },
      ).catch(() => null);
      if (!reply?.ok) {
        setMembers([]);
        setMembersError(reply?.status ?? 0);
        setMembersStatus("error");
        return;
      }
      const doc = (await reply.json()) as { members?: MemberRow[] };
      setMembers(doc.members ?? []);
      setMembersStatus("ok");
    },
    [tenantId, tenantHeaders],
  );

  const load = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setStatus("error");
      setErrorStatus(0);
      setGroups([]);
      return;
    }
    setStatus("loading");
    setNotice(null);
    const reply = await fetch(`/api/platform/inbox/groups?tenantId=${encodeURIComponent(tid)}`, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply?.ok) {
      setGroups([]);
      setSelectedId(null);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const doc = (await reply.json()) as { groups?: GroupRow[] };
    const rows = doc.groups ?? [];
    setGroups(rows);
    document.cookie = orgTenantCookieWrite(tid);
    setStatus("ok");
    setSelectedId((prev) => {
      if (prev && rows.some((r) => r.groupId === prev)) return prev;
      const first = rows[0]?.groupId ?? null;
      if (first) {
        const g = rows.find((r) => r.groupId === first);
        setEditName(g?.name ?? "");
        setEditDesc(g?.description ?? "");
        void loadMembers(first);
      } else {
        setMembers([]);
        setMembersStatus("idle");
      }
      return first;
    });
  }, [tenantId, tenantHeaders, loadMembers]);

  function selectGroup(groupId: string) {
    setSelectedId(groupId);
    const g = groups.find((r) => r.groupId === groupId);
    setEditName(g?.name ?? "");
    setEditDesc(g?.description ?? "");
    void loadMembers(groupId);
  }

  async function createGroup() {
    const tid = tenantId.trim();
    const name = newName.trim();
    if (!tid || !name) {
      setNotice(phrases.inboxGroupsNeedTenant);
      return;
    }
    setCreating(true);
    setNotice(null);
    const reply = await fetch(`/api/platform/inbox/groups?tenantId=${encodeURIComponent(tid)}`, {
      method: "POST",
      cache: "no-store",
      headers: { ...tenantHeaders(), "content-type": "application/json" },
      body: JSON.stringify({ name, description: newDesc.trim() || null }),
    }).catch(() => null);
    setCreating(false);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxGroupsCreateFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setNewName("");
    setNewDesc("");
    setNotice(phrases.inboxGroupsCreateDone);
    await load();
  }

  async function saveGroup() {
    const tid = tenantId.trim();
    if (!tid || !selectedId || !editName.trim()) return;
    setSaving(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/inbox/groups/${encodeURIComponent(selectedId)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PATCH",
        cache: "no-store",
        headers: { ...tenantHeaders(), "content-type": "application/json" },
        body: JSON.stringify({ name: editName.trim(), description: editDesc.trim() || null }),
      },
    ).catch(() => null);
    setSaving(false);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxGroupsSaveFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setNotice(phrases.inboxGroupsSaveDone);
    await load();
  }

  async function deleteGroup() {
    const tid = tenantId.trim();
    if (!tid || !selectedId) return;
    const g = groups.find((r) => r.groupId === selectedId);
    if (!window.confirm(fillPhrase(phrases.inboxGroupsDeleteConfirm, { name: g?.name ?? selectedId }))) {
      return;
    }
    setDeleting(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/inbox/groups/${encodeURIComponent(selectedId)}?tenantId=${encodeURIComponent(tid)}`,
      { method: "DELETE", cache: "no-store", headers: tenantHeaders() },
    ).catch(() => null);
    setDeleting(false);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxGroupsDeleteFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setSelectedId(null);
    setMembers([]);
    await load();
  }

  async function addMember() {
    const tid = tenantId.trim();
    const sid = memberId.trim();
    if (!tid || !selectedId || !sid) return;
    setAddingMember(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/inbox/groups/${encodeURIComponent(selectedId)}/members?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "POST",
        cache: "no-store",
        headers: { ...tenantHeaders(), "content-type": "application/json" },
        body: JSON.stringify({ memberSubjectId: sid }),
      },
    ).catch(() => null);
    setAddingMember(false);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxGroupsMemberAddFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setMemberId("");
    await loadMembers(selectedId);
  }

  async function removeMember(subjectId: string) {
    const tid = tenantId.trim();
    if (!tid || !selectedId || !subjectId) return;
    const reply = await fetch(
      `/api/platform/inbox/groups/${encodeURIComponent(selectedId)}/members/${encodeURIComponent(subjectId)}?tenantId=${encodeURIComponent(tid)}`,
      { method: "DELETE", cache: "no-store", headers: tenantHeaders() },
    ).catch(() => null);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxGroupsMemberRemoveFailed, { status: reply?.status ?? 0 }));
      return;
    }
    await loadMembers(selectedId);
  }

  return (
    <div className="mt-4 flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.inboxTenantLabel}
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder={phrases.inboxTenantPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded-md bg-foreground px-3 py-1.5 text-sm text-background hover:opacity-90"
        >
          {status === "loading" ? phrases.inboxRefreshAction : phrases.inboxGroupsLoadAction}
        </button>
      </div>

      {status === "error" ? (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {errorStatus === 0
            ? phrases.inboxGroupsNeedTenant
            : fillPhrase(phrases.inboxGroupsLoadFailed, { status: errorStatus })}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-sm text-muted">
          {notice}
        </p>
      ) : null}

      <div className="rounded-lg border border-border bg-surface p-3 text-sm">
        <h3 className="font-medium">{phrases.inboxGroupsCreateAction}</h3>
        <div className="mt-2 flex flex-wrap gap-2">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder={phrases.inboxGroupsNameLabel}
            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
          />
          <input
            value={newDesc}
            onChange={(e) => setNewDesc(e.target.value)}
            placeholder={phrases.inboxGroupsDescLabel}
            className="min-w-[12rem] flex-1 rounded-md border border-border bg-background px-2 py-1 text-sm"
          />
          <button
            type="button"
            disabled={creating}
            onClick={() => void createGroup()}
            className="rounded-md border border-border bg-background px-3 py-1.5 hover:bg-surface disabled:opacity-60"
          >
            {creating ? phrases.inboxGroupsCreating : phrases.inboxGroupsCreateAction}
          </button>
        </div>
      </div>

      {status === "ok" && groups.length === 0 ? (
        <p className="text-sm text-muted">{phrases.inboxGroupsEmpty}</p>
      ) : null}

      {groups.length > 0 ? (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
          <div className="overflow-hidden rounded-lg border border-border bg-surface">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-background text-left text-muted">
                <tr>
                  <th className="px-3 py-2 font-medium">{phrases.inboxGroupsNameLabel}</th>
                  <th className="px-3 py-2 font-medium">{phrases.inboxGroupsDescLabel}</th>
                </tr>
              </thead>
              <tbody>
                {groups.map((g) => {
                  const id = g.groupId ?? "";
                  const active = id === selectedId;
                  return (
                    <tr
                      key={id}
                      className={`cursor-pointer border-t border-border ${
                        active ? "bg-background" : "hover:bg-background/60"
                      }`}
                      onClick={() => id && selectGroup(id)}
                    >
                      <td className="px-3 py-2 font-medium">{g.name ?? id}</td>
                      <td className="px-3 py-2 text-muted">{g.description || "—"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <aside className="rounded-lg border border-border bg-surface p-4 text-sm">
            {selectedId ? (
              <div className="flex flex-col gap-3">
                <label className="flex flex-col gap-1">
                  {phrases.inboxGroupsNameLabel}
                  <input
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    className="rounded-md border border-border bg-background px-2 py-1"
                  />
                </label>
                <label className="flex flex-col gap-1">
                  {phrases.inboxGroupsDescLabel}
                  <input
                    value={editDesc}
                    onChange={(e) => setEditDesc(e.target.value)}
                    className="rounded-md border border-border bg-background px-2 py-1"
                  />
                </label>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => void saveGroup()}
                    className="rounded-md bg-foreground px-3 py-1.5 text-background hover:opacity-90 disabled:opacity-60"
                  >
                    {saving ? phrases.inboxGroupsSaving : phrases.inboxGroupsSaveAction}
                  </button>
                  <button
                    type="button"
                    disabled={deleting}
                    onClick={() => void deleteGroup()}
                    className="rounded-md border border-border bg-background px-3 py-1.5 hover:bg-surface disabled:opacity-60"
                  >
                    {deleting ? phrases.inboxGroupsDeleting : phrases.inboxGroupsDeleteAction}
                  </button>
                </div>

                <div className="mt-2 border-t border-border pt-3">
                  <h3 className="font-medium">{phrases.inboxGroupsMembersTitle}</h3>
                  {membersStatus === "error" ? (
                    <p role="alert" className="mt-1 text-xs text-red-700 dark:text-red-400">
                      {fillPhrase(phrases.inboxGroupsMembersLoadFailed, { status: membersError })}
                    </p>
                  ) : null}
                  {membersStatus === "ok" && members.length === 0 ? (
                    <p className="mt-1 text-xs text-muted">{phrases.inboxGroupsMembersEmpty}</p>
                  ) : null}
                  {members.length > 0 ? (
                    <ul className="mt-2 flex flex-col gap-1">
                      {members.map((m) => (
                        <li
                          key={m.memberSubjectId}
                          className="flex items-center justify-between gap-2 font-mono text-xs"
                        >
                          <span>{m.memberSubjectId}</span>
                          <button
                            type="button"
                            className="text-muted underline"
                            onClick={() => m.memberSubjectId && void removeMember(m.memberSubjectId)}
                          >
                            {phrases.inboxGroupsMemberRemoveAction}
                          </button>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  <div className="mt-2 flex flex-wrap gap-2">
                    <input
                      value={memberId}
                      onChange={(e) => setMemberId(e.target.value)}
                      placeholder={phrases.inboxGroupsMemberIdLabel}
                      className="min-w-[10rem] flex-1 rounded-md border border-border bg-background px-2 py-1 font-mono text-xs"
                    />
                    <button
                      type="button"
                      disabled={addingMember}
                      onClick={() => void addMember()}
                      className="rounded-md border border-border bg-background px-3 py-1.5 text-xs hover:bg-surface disabled:opacity-60"
                    >
                      {addingMember ? phrases.inboxGroupsMemberAdding : phrases.inboxGroupsMemberAddAction}
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <p className="text-muted">{phrases.emptyList}</p>
            )}
          </aside>
        </div>
      ) : null}
    </div>
  );
}
