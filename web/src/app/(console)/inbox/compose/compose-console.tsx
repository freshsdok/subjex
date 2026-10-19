"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  availableComposeModes,
  defaultComposeMode,
  INBOX_ATTACH_MAX_BYTES,
  INBOX_ATTACH_MAX_FILES,
  isAllowedInboxAttachmentType,
  parseIdList,
  validateComposeDraft,
  type InboxComposeMode,
} from "@/lib/inbox-compose";
import { orgTenantCookieWrite } from "@/lib/org-console";

type GroupRow = { groupId?: string; name?: string; description?: string | null };
type StagedFile = { stagingId: string; fileName: string; byteSize: number };

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  permissionNames: string[];
  /** Manifest button btn.inbox.compose.send — 发送按钮 Manifest 门禁（PC-4）。 */
  canSend: boolean;
};

/**
 * InboxComposeConsole — IM-A7 client compose (system / group / org + staging attach).
 * 写信控制台：三种发送方式 + 暂存附件。
 */
export function InboxComposeConsole({ phrases, initialTenantId, permissionNames, canSend }: Props) {
  const allowedModes = useMemo(() => availableComposeModes(permissionNames), [permissionNames]);
  const canUpload = permissionNames.includes("inbox.send.system");
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [mode, setMode] = useState<InboxComposeMode>(() => defaultComposeMode(allowedModes) ?? "system");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [severity, setSeverity] = useState("INFO");
  const [deepLink, setDeepLink] = useState("");
  const [recipientsRaw, setRecipientsRaw] = useState("");
  const [groupId, setGroupId] = useState("");
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [groupsStatus, setGroupsStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [groupsError, setGroupsError] = useState(0);
  const [orgIdsRaw, setOrgIdsRaw] = useState("");
  const [includeDescendants, setIncludeDescendants] = useState(true);
  const [attachments, setAttachments] = useState<StagedFile[]>([]);
  const [attachNotice, setAttachNotice] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const tenantHeaders = useCallback((): Record<string, string> => {
    const headers: Record<string, string> = { accept: "application/json" };
    const tid = tenantId.trim();
    if (tid) headers["X-Tenant-Id"] = tid;
    return headers;
  }, [tenantId]);

  const loadGroups = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid || mode !== "group") return;
    setGroupsStatus("loading");
    const reply = await fetch(`/api/platform/inbox/groups?tenantId=${encodeURIComponent(tid)}`, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply?.ok) {
      setGroups([]);
      setGroupsError(reply?.status ?? 0);
      setGroupsStatus("error");
      return;
    }
    const doc = (await reply.json()) as { groups?: GroupRow[] };
    setGroups(doc.groups ?? []);
    setGroupsStatus("ok");
  }, [tenantId, mode, tenantHeaders]);

  useEffect(() => {
    void loadGroups();
  }, [loadGroups]);

  async function uploadFiles(fileList: FileList | null) {
    setAttachNotice(null);
    if (!canUpload) {
      setAttachNotice(phrases.inboxComposeAttachForbidden);
      return;
    }
    if (!fileList || fileList.length === 0) return;
    const tid = tenantId.trim();
    if (!tid) {
      setAttachNotice(phrases.inboxComposeNeedTenant);
      return;
    }
    const room = INBOX_ATTACH_MAX_FILES - attachments.length;
    if (room <= 0) {
      setAttachNotice(phrases.inboxComposeAttachTooMany);
      return;
    }
    const picked = Array.from(fileList).slice(0, room);
    const next: StagedFile[] = [];
    for (const file of picked) {
      if (file.size > INBOX_ATTACH_MAX_BYTES) {
        setAttachNotice(fillPhrase(phrases.inboxComposeAttachTooBig, { name: file.name }));
        continue;
      }
      const contentType = file.type || "application/octet-stream";
      if (!isAllowedInboxAttachmentType(contentType)) {
        setAttachNotice(fillPhrase(phrases.inboxComposeAttachBadType, { name: file.name }));
        continue;
      }
      const params = new URLSearchParams({
        tenantId: tid,
        fileName: file.name,
        contentType,
      });
      const reply = await fetch(`/api/platform/inbox/attachments?${params}`, {
        method: "POST",
        cache: "no-store",
        headers: {
          ...tenantHeaders(),
          "content-type": "application/octet-stream",
          accept: "application/json",
        },
        body: await file.arrayBuffer(),
      }).catch(() => null);
      if (!reply?.ok) {
        setAttachNotice(
          fillPhrase(phrases.inboxComposeAttachFailed, { status: reply?.status ?? 0, name: file.name }),
        );
        continue;
      }
      const staged = (await reply.json()) as { stagingId?: string; fileName?: string; byteSize?: number };
      if (staged.stagingId) {
        next.push({
          stagingId: staged.stagingId,
          fileName: staged.fileName ?? file.name,
          byteSize: staged.byteSize ?? file.size,
        });
      }
    }
    if (next.length > 0) {
      setAttachments((prev) => [...prev, ...next].slice(0, INBOX_ATTACH_MAX_FILES));
    }
    if (fileRef.current) fileRef.current.value = "";
  }

  async function send() {
    setNotice(null);
    setError(null);
    const recipientSubjectIds = parseIdList(recipientsRaw);
    const orgIds = parseIdList(orgIdsRaw);
    const validation = validateComposeDraft({
      mode,
      allowedModes,
      tenantId,
      title,
      body,
      recipientSubjectIds,
      groupId,
      orgIds,
      attachmentCount: attachments.length,
    });
    if (validation) {
      const map: Record<string, string> = {
        needTenant: phrases.inboxComposeNeedTenant,
        needTitle: phrases.inboxComposeNeedTitle,
        needBody: phrases.inboxComposeNeedBody,
        needRecipients: phrases.inboxComposeNeedRecipients,
        needGroup: phrases.inboxComposeNeedGroup,
        needOrgs: phrases.inboxComposeNeedOrgs,
        tooManyAttachments: phrases.inboxComposeAttachTooMany,
        modeForbidden: phrases.inboxComposeModeForbidden,
      };
      setError(map[validation] ?? phrases.inboxComposeModeForbidden);
      return;
    }
    const tid = tenantId.trim();
    document.cookie = orgTenantCookieWrite(tid);
    const stagingIds = attachments.map((a) => a.stagingId);
    const deep = deepLink.trim();
    let path = "";
    let payload: Record<string, unknown> = {};
    if (mode === "system") {
      path = "/api/platform/inbox/system";
      payload = {
        title: title.trim(),
        body: body.trim(),
        severity,
        recipientSubjectIds,
        deepLink: deep || null,
        attachmentStagingIds: stagingIds,
      };
    } else if (mode === "group") {
      path = "/api/platform/inbox/group-send";
      payload = {
        groupId: groupId.trim(),
        title: title.trim(),
        body: body.trim(),
        severity,
        deepLink: deep || null,
        attachmentStagingIds: stagingIds,
      };
    } else {
      path = "/api/platform/inbox/broadcast";
      payload = {
        orgIds,
        includeDescendants,
        title: title.trim(),
        body: body.trim(),
        severity,
        deepLink: deep || null,
        attachmentStagingIds: stagingIds,
      };
    }
    if (!canSend) return;
    setSending(true);
    const reply = await fetch(`${path}?tenantId=${encodeURIComponent(tid)}`, {
      method: "POST",
      cache: "no-store",
      headers: { ...tenantHeaders(), "content-type": "application/json" },
      body: JSON.stringify(payload),
    }).catch(() => null);
    setSending(false);
    if (!reply?.ok) {
      setError(fillPhrase(phrases.inboxComposeSendFailed, { status: reply?.status ?? 0 }));
      return;
    }
    const result = (await reply.json()) as {
      messageId?: string;
      fanoutStatus?: string;
      fanoutJobId?: string | null;
    };
    const fanoutStatus = result.fanoutStatus ?? "COMPLETE";
    if (fanoutStatus === "PENDING") {
      setNotice(
        fillPhrase(phrases.inboxComposeSendDonePending, {
          messageId: result.messageId ?? "—",
          fanoutJobId: result.fanoutJobId ?? "—",
        }),
      );
    } else {
      setNotice(
        fillPhrase(phrases.inboxComposeSendDone, {
          messageId: result.messageId ?? "—",
          fanoutStatus,
        }),
      );
    }
    setAttachments([]);
  }

  if (allowedModes.length === 0) {
    return <p className="mt-4 text-sm text-muted">{phrases.inboxComposeEmptyModes}</p>;
  }

  return (
    <div className="mt-4 flex max-w-3xl flex-col gap-4">
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
        <label className="flex flex-col gap-1 text-sm">
          {phrases.inboxComposeModeLabel}
          <select
            value={mode}
            onChange={(e) => setMode(e.target.value as InboxComposeMode)}
            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
          >
            {allowedModes.includes("system") ? (
              <option value="system">{phrases.inboxComposeModeSystem}</option>
            ) : null}
            {allowedModes.includes("group") ? (
              <option value="group">{phrases.inboxComposeModeGroup}</option>
            ) : null}
            {allowedModes.includes("org") ? (
              <option value="org">{phrases.inboxComposeModeOrg}</option>
            ) : null}
          </select>
        </label>
      </div>

      <label className="flex flex-col gap-1 text-sm">
        {phrases.inboxComposeTitleLabel}
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className="rounded-md border border-border bg-background px-2 py-1 text-sm"
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {phrases.inboxComposeBodyLabel}
        <textarea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          rows={6}
          className="rounded-md border border-border bg-background px-2 py-1 text-sm"
        />
      </label>
      <div className="flex flex-wrap gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.inboxComposeSeverityLabel}
          <select
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
          >
            <option value="INFO">{phrases.inboxSeverityInfo}</option>
            <option value="WARN">{phrases.inboxSeverityWarn}</option>
            <option value="ACTION">{phrases.inboxSeverityAction}</option>
          </select>
        </label>
        <label className="flex min-w-[16rem] flex-1 flex-col gap-1 text-sm">
          {phrases.inboxComposeDeepLinkLabel}
          <input
            value={deepLink}
            onChange={(e) => setDeepLink(e.target.value)}
            placeholder="/inbox"
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
          />
        </label>
      </div>

      {mode === "system" ? (
        <label className="flex flex-col gap-1 text-sm">
          {phrases.inboxComposeRecipientsLabel}
          <textarea
            value={recipientsRaw}
            onChange={(e) => setRecipientsRaw(e.target.value)}
            rows={3}
            placeholder={phrases.inboxComposeRecipientsPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
          />
          <span className="text-xs text-muted">{phrases.inboxComposeRecipientsHint}</span>
        </label>
      ) : null}

      {mode === "group" ? (
        <div className="flex flex-col gap-1 text-sm">
          <label className="flex flex-col gap-1">
            {phrases.inboxComposeGroupLabel}
            <select
              value={groupId}
              onChange={(e) => setGroupId(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{phrases.inboxComposeGroupPlaceholder}</option>
              {groups.map((g) => (
                <option key={g.groupId} value={g.groupId}>
                  {g.name ?? g.groupId}
                </option>
              ))}
            </select>
          </label>
          {groupsStatus === "error" ? (
            <p role="alert" className="text-xs text-red-700 dark:text-red-400">
              {fillPhrase(phrases.inboxComposeGroupLoadFailed, { status: groupsError })}
            </p>
          ) : null}
          {groupsStatus === "ok" && groups.length === 0 ? (
            <p className="text-xs text-muted">
              {phrases.inboxComposeGroupEmpty}{" "}
              <Link href="/inbox/groups" className="underline">
                {phrases.inboxGroupsLink}
              </Link>
            </p>
          ) : null}
        </div>
      ) : null}

      {mode === "org" ? (
        <div className="flex flex-col gap-2 text-sm">
          <label className="flex flex-col gap-1">
            {phrases.inboxComposeOrgIdsLabel}
            <input
              value={orgIdsRaw}
              onChange={(e) => setOrgIdsRaw(e.target.value)}
              placeholder={phrases.inboxComposeOrgIdsPlaceholder}
              className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            />
            <span className="text-xs text-muted">{phrases.inboxComposeOrgIdsHint}</span>
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={includeDescendants}
              onChange={(e) => setIncludeDescendants(e.target.checked)}
            />
            {phrases.inboxComposeIncludeDescendants}
          </label>
        </div>
      ) : null}

      <div className="rounded-lg border border-border bg-surface p-3 text-sm">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="font-medium">{phrases.inboxComposeAttachmentsLabel}</h3>
          <button
            type="button"
            disabled={!canUpload || attachments.length >= INBOX_ATTACH_MAX_FILES}
            onClick={() => fileRef.current?.click()}
            className="rounded-md border border-border bg-background px-3 py-1.5 text-sm hover:bg-surface disabled:opacity-50"
          >
            {phrases.inboxComposeAttachAction}
          </button>
          <input
            ref={fileRef}
            type="file"
            className="hidden"
            multiple
            onChange={(e) => void uploadFiles(e.target.files)}
          />
        </div>
        <p className="mt-1 text-xs text-muted">{phrases.inboxComposeAttachHint}</p>
        {!canUpload ? (
          <p className="mt-1 text-xs text-amber-800 dark:text-amber-200">{phrases.inboxComposeAttachForbidden}</p>
        ) : null}
        {attachNotice ? (
          <p role="status" className="mt-2 text-xs text-muted">
            {attachNotice}
          </p>
        ) : null}
        {attachments.length > 0 ? (
          <ul className="mt-2 flex flex-col gap-1">
            {attachments.map((a) => (
              <li key={a.stagingId} className="flex items-center justify-between gap-2 font-mono text-xs">
                <span>
                  {a.fileName} ({a.byteSize} B)
                </span>
                <button
                  type="button"
                  className="text-muted underline"
                  onClick={() => setAttachments((prev) => prev.filter((x) => x.stagingId !== a.stagingId))}
                >
                  {phrases.inboxComposeRemoveAttach}
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      {error ? (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-sm text-muted">
          {notice}
        </p>
      ) : null}

      <div>
        <button
          type="button"
          disabled={sending || !canSend}
          onClick={() => void send()}
          className="rounded-md bg-foreground px-4 py-2 text-sm text-background hover:opacity-90 disabled:opacity-60"
        >
          {sending ? phrases.inboxComposeSending : phrases.inboxComposeSendAction}
        </button>
      </div>
    </div>
  );
}
