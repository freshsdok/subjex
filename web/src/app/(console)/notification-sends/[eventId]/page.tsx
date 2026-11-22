import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  notificationSendChannelPhraseKey,
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
 * Notification send detail — read-only template, channel, recipient, status, and dead-letter link.
 * Enter: Manifest {@code page.notification-sends} ({@code notification.read}). No send, retry, or replay actions.
 * <p>
 * 发送记录详情：只读展示模板、渠道、收件人、状态，以及死信链接。进入走 page.notification-sends。不能发送、重试或重放。
 */
export default async function NotificationSendDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ eventId: string }>;
  searchParams: Promise<SearchBag>;
}) {
  const { language, phrases } = await currentLanguage();
  const { eventId: rawId } = await params;
  const query = await searchParams;
  const eventId = decodeURIComponent(rawId || "").trim();
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
  const tenantId = first(query.tenantId) || cookieTenant;
  const backQs = notificationSendListQueryString({
    tenantId,
    status: first(query.status),
    channel: first(query.channel),
    recipient: first(query.recipient),
    from: first(query.from),
    to: first(query.to),
  });
  const backHref = `/notification-sends${backQs ? `?${backQs}` : ""}`;
  const backLink = (
    <p className="mb-4 text-sm">
      <Link href={backHref} className="text-accent hover:underline">
        {phrases.notificationSendsBackToList}
      </Link>
    </p>
  );

  if (!tenantId) {
    return (
      <section>
        <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.notificationSendsMissingTenant}</p>
      </section>
    );
  }
  if (!eventId) {
    return (
      <section>
        <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.notificationSendsNotFound}</p>
      </section>
    );
  }

  const read = await readPlatform<NotificationSendDocumentView>(
    `notification-sends/${encodeURIComponent(eventId)}`,
    { tenantId },
  );
  if (read.status === 403) {
    return (
      <section>
        <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
        {backLink}
        <ForbiddenNotice phrases={phrases} permission="notification.read" />
      </section>
    );
  }
  if (read.status === 404) {
    return (
      <section>
        <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
        {backLink}
        <p className="text-sm text-muted">{phrases.notificationSendsNotFound}</p>
      </section>
    );
  }
  if (!read.body) {
    return (
      <section>
        <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
        {backLink}
        <LoadFailedNotice phrases={phrases} status={read.status} />
      </section>
    );
  }

  const doc = read.body;
  const locale = language === "zh" ? "zh-CN" : "en";
  const timeFormat = new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "medium" });
  const statusKey = notificationSendStatusPhraseKey(doc.status);
  const channelKey = notificationSendChannelPhraseKey(doc.channel);
  const parameters = Object.entries(doc.parameters ?? {});
  return (
    <section>
      <PageHeading title={phrases.notificationSendsTitle} hint={phrases.notificationSendsHint} />
      {backLink}
      <dl className="mb-6 grid gap-3 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2">
        <Detail itemLabel={phrases.notificationSendsRecordIdLabel} value={doc.eventId} mono />
        <Detail
          itemLabel={phrases.notificationSendsStatusLabel}
          value={statusKey ? phrases[statusKey] : doc.status || ""}
        />
        <Detail
          itemLabel={phrases.notificationSendsChannelLabel}
          value={channelKey ? phrases[channelKey] : doc.channel || ""}
        />
        <Detail itemLabel={phrases.notificationSendsTemplateLabel} value={doc.templateId || ""} />
        <Detail itemLabel={phrases.notificationSendsRecipientLabel} value={notificationSendRecipientLine(phrases, doc)} />
        <Detail itemLabel={phrases.notificationSendsAttemptsLabel} value={String(doc.attemptCount ?? "")} />
        <Detail
          itemLabel={phrases.notificationSendsOccurredAtLabel}
          value={doc.occurredAt ? timeFormat.format(new Date(doc.occurredAt)) : ""}
        />
        <Detail
          itemLabel={phrases.notificationSendsPublishedAtLabel}
          value={doc.publishedAt ? timeFormat.format(new Date(doc.publishedAt)) : ""}
        />
        <Detail itemLabel={phrases.notificationSendsFailureReasonLabel} value={doc.failureReason || ""} />
        <div className="flex flex-col gap-1">
          <dt className="text-xs text-muted">{phrases.notificationSendsDeadLetterLabel}</dt>
          <dd>
            {doc.deadLetterId ? (
              <Link href="/dead-letters" className="text-accent hover:underline">
                {phrases.notificationSendsDeadLetterLink}
              </Link>
            ) : (
              phrases.notificationSendsDeadLetterNone
            )}
          </dd>
        </div>
      </dl>
      {parameters.length > 0 ? (
        <section>
          <h2 className="mb-2 text-base font-medium">{phrases.notificationSendsTemplateLabel}</h2>
          <dl className="grid gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2">
            {parameters.map(([key, value]) => (
              <Detail key={key} itemLabel={key} value={value} mono />
            ))}
          </dl>
        </section>
      ) : null}
    </section>
  );
}

function Detail({
  itemLabel,
  value,
  mono,
}: {
  itemLabel: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-muted">{itemLabel}</dt>
      <dd className={mono ? "font-mono text-xs break-all" : ""}>{value}</dd>
    </div>
  );
}

function first(value: string | undefined): string {
  return (value ?? "").trim();
}
