/**
 * Inbox compose helpers (IM-A7) — mode/recipient/attachment guards shared by UI + tests.
 * 站内信撰写辅助：模式、收件人、附件门禁（UI 与测试共用）。
 */

/** Compose target mode — 撰写目标模式. */
export type InboxComposeMode = "system" | "group" | "org";

/** ADR 0009 / platform caps — 与平台合同上限对齐. */
export const INBOX_ATTACH_MAX_FILES = 5;
export const INBOX_ATTACH_MAX_BYTES = 5 * 1024 * 1024;

/** MIME allowlist (matches InboxAttachmentContentTypes) — MIME 白名单. */
export const INBOX_ATTACH_ALLOWED_TYPES = [
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/webp",
  "text/plain",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
] as const;

/**
 * Split subject / org ids from comma, whitespace, or newline lists; drop blanks; dedupe order-preserving.
 * 从逗号/空白/换行拆主体或组织 id；去空；保序去重。
 */
export function parseIdList(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of (raw ?? "").split(/[\s,;]+/)) {
    const id = part.trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

/** Whether MIME is in the upload allowlist — MIME 是否在上传白名单. */
export function isAllowedInboxAttachmentType(contentType: string): boolean {
  const ct = (contentType ?? "").trim().toLowerCase();
  if (!ct) return false;
  return (INBOX_ATTACH_ALLOWED_TYPES as readonly string[]).includes(ct);
}

/**
 * Which compose modes the operator may use given permission names.
 * 按权限决定可用撰写模式。
 */
export function availableComposeModes(permissionNames: Iterable<string>): InboxComposeMode[] {
  const set = permissionNames instanceof Set ? permissionNames : new Set(permissionNames);
  const modes: InboxComposeMode[] = [];
  if (set.has("inbox.send.system")) modes.push("system");
  if (set.has("inbox.broadcast")) {
    modes.push("group");
    modes.push("org");
  }
  return modes;
}

/** True when operator can open compose at all — 是否可打开撰写页. */
export function canOpenCompose(permissionNames: Iterable<string>): boolean {
  return availableComposeModes(permissionNames).length > 0;
}

/**
 * Pick initial mode preferring system, then group, then org.
 * 默认模式优先系统，再组发，再组织广播。
 */
export function defaultComposeMode(modes: InboxComposeMode[]): InboxComposeMode | null {
  if (modes.includes("system")) return "system";
  if (modes.includes("group")) return "group";
  if (modes.includes("org")) return "org";
  return null;
}

/**
 * Validate compose fields before POST; returns Chinese-first machine keys for UI to map to phrases.
 * 发送前校验；返回机器键供 UI 映射文案。
 */
export type ComposeValidationError =
  | "needTenant"
  | "needTitle"
  | "needBody"
  | "needRecipients"
  | "needGroup"
  | "needOrgs"
  | "tooManyAttachments"
  | "modeForbidden";

export function validateComposeDraft(input: {
  mode: InboxComposeMode;
  allowedModes: InboxComposeMode[];
  tenantId: string;
  title: string;
  body: string;
  recipientSubjectIds: string[];
  groupId: string;
  orgIds: string[];
  attachmentCount: number;
}): ComposeValidationError | null {
  if (!input.allowedModes.includes(input.mode)) return "modeForbidden";
  if (!input.tenantId.trim()) return "needTenant";
  if (!input.title.trim()) return "needTitle";
  if (!input.body.trim()) return "needBody";
  if (input.attachmentCount > INBOX_ATTACH_MAX_FILES) return "tooManyAttachments";
  if (input.mode === "system" && input.recipientSubjectIds.length === 0) return "needRecipients";
  if (input.mode === "group" && !input.groupId.trim()) return "needGroup";
  if (input.mode === "org" && input.orgIds.length === 0) return "needOrgs";
  return null;
}
