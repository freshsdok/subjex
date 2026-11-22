import type { PhraseBook } from "@/i18n/phrases";

/**
 * Operator notification send-record console helpers — 操作员通知发送记录控制台辅助。
 * Maps status, channel, and error codes for the read-only list and detail pages.
 * 为只读列表和详情页映射状态、渠道与错误码。不提供发送、重试或重放动作。
 */

export type NotificationSendDocumentView = {
  eventId: string;
  tenantId?: string;
  templateId?: string | null;
  channel?: string | null;
  recipientSubjectId?: string | null;
  recipientRealm?: string | null;
  recipientDestination?: string | null;
  parameters?: Record<string, string>;
  status?: string;
  attemptCount?: number;
  failureReason?: string | null;
  occurredAt?: string;
  publishedAt?: string | null;
  deadLetterId?: string | null;
  deadLetteredAt?: string | null;
};

export const NOTIFICATION_SEND_STATUS_VALUES = ["PENDING", "PUBLISHED", "DEAD"] as const;
export type NotificationSendStatusValue = (typeof NOTIFICATION_SEND_STATUS_VALUES)[number];

export const NOTIFICATION_SEND_CHANNEL_VALUES = ["SMS", "EMAIL", "WECHAT_TEMPLATE"] as const;
export type NotificationSendChannelValue = (typeof NOTIFICATION_SEND_CHANNEL_VALUES)[number];

const STATUS_PHRASE_KEYS = {
  PENDING: "notificationSendsStatusPending",
  PUBLISHED: "notificationSendsStatusPublished",
  DEAD: "notificationSendsStatusDead",
} as const;

const CHANNEL_PHRASE_KEYS = {
  SMS: "notificationSendsChannelSms",
  EMAIL: "notificationSendsChannelEmail",
  WECHAT_TEMPLATE: "notificationSendsChannelWechat",
} as const;

/** Phrase key for a send status — 发送状态对应的文案键. */
export function notificationSendStatusPhraseKey(
  status?: string,
): (typeof STATUS_PHRASE_KEYS)[NotificationSendStatusValue] | undefined {
  const name = (status || "").trim().toUpperCase();
  return STATUS_PHRASE_KEYS[name as NotificationSendStatusValue];
}

/** Phrase key for a channel — 渠道对应的文案键. */
export function notificationSendChannelPhraseKey(
  channel?: string | null,
): (typeof CHANNEL_PHRASE_KEYS)[NotificationSendChannelValue] | undefined {
  const name = (channel || "").trim().toUpperCase();
  return CHANNEL_PHRASE_KEYS[name as NotificationSendChannelValue];
}

/** Phrase key for a known send-log query error reason — 发送记录查询错误原因对应的文案键. */
export function notificationSendQueryErrorPhraseKey(
  reason?: string,
): "notificationSendsNotFound" | "notificationSendsCrossTenantForbidden" | undefined {
  switch ((reason || "").trim()) {
    case "notification-send-not-found":
      return "notificationSendsNotFound";
    case "notification-cross-tenant-denied":
      return "notificationSendsCrossTenantForbidden";
    default:
      return undefined;
  }
}

/** Whether the list API should send a status filter — 列表查询是否应带上状态筛选. */
export function apiNotificationSendStatusFilter(raw?: string): string {
  const name = (raw || "").trim().toUpperCase();
  if (!name || name === "ALL") return "";
  return NOTIFICATION_SEND_STATUS_VALUES.includes(name as NotificationSendStatusValue) ? name : "";
}

/** Whether the list API should send a channel filter — 列表查询是否应带上渠道筛选. */
export function apiNotificationSendChannelFilter(raw?: string): string {
  const name = (raw || "").trim().toUpperCase();
  if (!name || name === "ALL") return "";
  return NOTIFICATION_SEND_CHANNEL_VALUES.includes(name as NotificationSendChannelValue) ? name : "";
}

/**
 * Drop empty or pattern-like recipient text so the API keeps a literal match.
 * 空值或含通配符的收件人筛选不传给接口，避免被当成模式。
 */
export function apiNotificationSendRecipientFilter(raw?: string): string {
  const value = (raw || "").trim();
  if (!value) return "";
  if (value.includes("%") || value.includes("_") || value.includes("\\") || value.includes('"')) {
    return "";
  }
  return value;
}

/**
 * Convert a datetime-local or ISO instant string to an API timestamp, or empty if invalid.
 * 把本地日期时间或 ISO 时间转为接口时间戳；无效则不传。
 */
export function apiNotificationSendTimeFilter(raw?: string): string {
  const value = (raw || "").trim();
  if (!value) return "";
  if (/Z$/i.test(value) || /[+-]\d{2}:\d{2}$/.test(value)) {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString();
  }
  const local = value.length === 16 ? `${value}:00` : value;
  const parsed = new Date(local);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString();
}

/**
 * Value for a {@code datetime-local} input from a query string or ISO instant.
 * 把查询串或 ISO 时间转成日期时间控件可回填的值。
 */
export function notificationSendDateTimeLocalValue(raw?: string): string {
  const value = (raw || "").trim();
  if (!value) return "";
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value) && !/(Z|[+-]\d{2}:\d{2})$/i.test(value)) {
    return value.slice(0, 16);
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}

/** Human recipient line: realm, subject id, and destination when present. / 收件人展示：领域、主体编号与目的地（如有）. */
export function notificationSendRecipientLine(
  phrases: PhraseBook,
  row: Pick<NotificationSendDocumentView, "recipientRealm" | "recipientSubjectId" | "recipientDestination">,
): string {
  const realm = (row.recipientRealm || "").trim().toUpperCase();
  const who =
    realm === "END_USER"
      ? phrases.notificationSendsRecipientEndUser
      : realm === "OPERATOR"
        ? phrases.notificationSendsRecipientOperator
        : realm;
  const parts = [who, (row.recipientSubjectId || "").trim(), (row.recipientDestination || "").trim()].filter(Boolean);
  return parts.join(" ");
}

/** Preserve list filters when moving between the list and a detail page. / 在列表与详情之间保留筛选条件. */
export function notificationSendListQueryString(filters: {
  tenantId?: string;
  status?: string;
  channel?: string;
  recipient?: string;
  from?: string;
  to?: string;
}): string {
  const query = new URLSearchParams();
  const tenantId = (filters.tenantId || "").trim();
  const status = (filters.status || "").trim();
  const channel = (filters.channel || "").trim();
  const recipient = (filters.recipient || "").trim();
  const from = (filters.from || "").trim();
  const to = (filters.to || "").trim();
  if (tenantId) query.set("tenantId", tenantId);
  if (status) query.set("status", status);
  if (channel) query.set("channel", channel);
  if (recipient) query.set("recipient", recipient);
  if (from) query.set("from", from);
  if (to) query.set("to", to);
  return query.toString();
}
