import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  apiNotificationSendChannelFilter,
  apiNotificationSendRecipientFilter,
  apiNotificationSendStatusFilter,
  apiNotificationSendTimeFilter,
  notificationSendChannelPhraseKey,
  notificationSendDateTimeLocalValue,
  notificationSendListQueryString,
  notificationSendRecipientLine,
  notificationSendStatusPhraseKey,
  type NotificationSendDocumentView,
} from "@/lib/notification-sends-console";
import {
  PAGE_NOTIFICATION_SENDS,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";

type SearchBag = {
  tenantId?: string;
  status?: string;
  channel?: string;
  recipient?: string;
  from?: string;
  to?: string;
};

/**
 * Notification send records — read-only operator list.
 * Enter: Manifest {@code page.notification-sends} ({@code notification.read}). No send, retry, or replay actions.
 * <p>
 * 通知发送记录：操作员只读列表。进入走 page.notification-sends（notification.read）。不能发送、重试或重放。
 */
export default async function NotificationSendsPage({
  searchParams,
}: {
  searchParams: Promise<SearchBag>;
}) {
  const { language, phrases } = await currentLanguage();
  const params = await searchParams;
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_NOTIFICATION_SENDS, permissions.has("notification.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="notification.read" />;
  }

  const jar = await cookies();
  const cookieTenant = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  const tenantId = first(params.tenantId) || cookieTenant;
  const statusFilter = first(params.status);
  const channelFilter = first(params.channel);
  const recipient = first(params.recipient);
  const from = first(params.from);
  const to = first(params.to);

  return (
    <section>
      <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
      <FilterForm
        phrases={phrases}
        tenantId={tenantId}
        statusFilter={statusFilter}
        channelFilter={channelFilter}
        recipient={recipient}
        from={from}
        to={to}
      />
      {!tenantId ? (
        <p className="text-sm text-muted">{phrases.notificationSendsMissingTenant}</p>
      ) : (
        <ListBody
          tenantId={tenantId}
          statusFilter={statusFilter}
          channelFilter={channelFilter}
          recipient={recipient}
          from={from}
          to={to}
          phrases={phrases}
          language={language}
        />
      )}
    </section>
  );
}

function FilterForm({
  phrases,
  tenantId,
  statusFilter,
  channelFilter,
  recipient,
  from,
  to,
}: {
  phrases: PhraseBook;
  tenantId: string;
  statusFilter: string;
  channelFilter: string;
  recipient: string;
  from: string;
  to: string;
}) {
  return (
    <form
      method="get"
      action="/notification-sends"
      className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface px-3 py-3 text-sm"
    >
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsTenantLabel}</span>
        <input
          name="tenantId"
          type="text"
          defaultValue={tenantId}
          placeholder={phrases.notificationSendsTenantPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsStatusLabel}</span>
        <select
          name="status"
          defaultValue={apiNotificationSendStatusFilter(statusFilter) || "all"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="all">{phrases.notificationSendsStatusAll}</option>
          <option value="PENDING">{phrases.notificationSendsStatusPending}</option>
          <option value="PUBLISHED">{phrases.notificationSendsStatusPublished}</option>
          <option value="DEAD">{phrases.notificationSendsStatusDead}</option>
        </select>
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsChannelLabel}</span>
        <select
          name="channel"
          defaultValue={apiNotificationSendChannelFilter(channelFilter) || "all"}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        >
          <option value="all">{phrases.notificationSendsChannelAll}</option>
          <option value="SMS">{phrases.notificationSendsChannelSms}</option>
          <option value="EMAIL">{phrases.notificationSendsChannelEmail}</option>
          <option value="WECHAT_TEMPLATE">{phrases.notificationSendsChannelWechat}</option>
        </select>
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsRecipientLabel}</span>
        <input
          name="recipient"
          type="text"
          defaultValue={recipient}
          placeholder={phrases.notificationSendsRecipientPlaceholder}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsFromLabel}</span>
        <input
          name="from"
          type="datetime-local"
          defaultValue={notificationSendDateTimeLocalValue(from)}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <label className="flex min-w-[10rem] flex-col gap-1">
        <span className="text-xs text-muted">{phrases.notificationSendsToLabel}</span>
        <input
          name="to"
          type="datetime-local"
          defaultValue={notificationSendDateTimeLocalValue(to)}
          className="rounded-md border border-border bg-background px-2 py-1.5"
        />
      </label>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="rounded-md bg-accent px-3 py-1.5 text-white">
          {phrases.notificationSendsSearchAction}
        </button>
        <Link href="/notification-sends" className="rounded-md border border-border px-3 py-1.5 hover:bg-background">
          {phrases.notificationSendsClearAction}
        </Link>
      </div>
    </form>
  );
}

async function ListBody({
  tenantId,
  statusFilter,
  channelFilter,
  recipient,
  from,
  to,
  phrases,
  language,
}: {
  tenantId: string;
  statusFilter: string;
  channelFilter: string;
  recipient: string;
  from: string;
  to: string;
  phrases: PhraseBook;
  language: "zh" | "en";
}) {
  const query = new URLSearchParams();
  const status = apiNotificationSendStatusFilter(statusFilter);
  const channel = apiNotificationSendChannelFilter(channelFilter);
  const recipientFilter = apiNotificationSendRecipientFilter(recipient);
  const fromFilter = apiNotificationSendTimeFilter(from);
  const toFilter = apiNotificationSendTimeFilter(to);
  if (status) query.set("status", status);
  if (channel) query.set("channel", channel);
  if (recipientFilter) query.set("recipient", recipientFilter);
  if (fromFilter) query.set("from", fromFilter);
  if (toFilter) query.set("to", toFilter);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const read = await readPlatform<NotificationSendDocumentView[]>(`notification-sends${suffix}`, { tenantId });
  if (read.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="notification.read" />;
  }
  if (!read.body) {
    return <LoadFailedNotice phrases={phrases} status={read.status} />;
  }
  const rows = read.body;
  if (rows.length === 0) {
    return <p className="text-sm text-muted">{phrases.notificationSendsEmpty}</p>;
  }
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "short",
  });
  const listQs = notificationSendListQueryString({
    tenantId,
    status: statusFilter,
    channel: channelFilter,
    recipient,
    from,
    to,
  });
  return (
    <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
      <thead className="bg-background text-left text-muted">
        <tr>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsOccurredAtLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsStatusLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsChannelLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsTemplateLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsRecipientLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsAttemptsLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.notificationSendsDeadLetterLabel}</th>
          <th className="px-3 py-2 font-medium" />
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const statusKey = notificationSendStatusPhraseKey(row.status);
          const channelKey = notificationSendChannelPhraseKey(row.channel);
          const detailHref = `/notification-sends/${encodeURIComponent(row.eventId)}${listQs ? `?${listQs}` : ""}`;
          return (
            <tr key={row.eventId} className="border-t border-border">
              <td className="whitespace-nowrap px-3 py-2">
                {row.occurredAt ? timeFormat.format(new Date(row.occurredAt)) : ""}
              </td>
              <td className="px-3 py-2">{statusKey ? phrases[statusKey] : row.status}</td>
              <td className="px-3 py-2">{channelKey ? phrases[channelKey] : row.channel || ""}</td>
              <td className="px-3 py-2">{row.templateId || ""}</td>
              <td className="px-3 py-2">{notificationSendRecipientLine(phrases, row)}</td>
              <td className="px-3 py-2">{row.attemptCount ?? ""}</td>
              <td className="px-3 py-2">
                {row.deadLetterId ? (
                  <Link href="/dead-letters" className="text-accent hover:underline">
                    {phrases.notificationSendsDeadLetterLink}
                  </Link>
                ) : (
                  ""
                )}
              </td>
              <td className="px-3 py-2">
                <Link href={detailHref} className="text-accent hover:underline">
                  {phrases.notificationSendsDetailAction}
                </Link>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}
