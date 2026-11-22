import type { ReactNode } from "react";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { PAGE_RATE_LIMITS, pageAllowed, type UiPermissionManifestDocument } from "@/lib/ui-permissions";
import { circuitStatePhraseKey, rateLimitBackendPhraseKey } from "@/lib/rate-limits-observation";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { RateLimitsRefreshButton } from "./rate-limits-refresh-button";

type ObservationDocument = {
  collectedAt?: string;
  rateLimit?: {
    backend?: string;
    processLocal?: boolean;
    permits?: number;
    windowSeconds?: number;
    windows?: Array<{
      tenantId?: string;
      actionName?: string;
      windowStartedAt?: string;
      windowEndsAt?: string;
      used?: number;
      remaining?: number;
    }>;
    truncated?: boolean;
    rejectionCountRecorded?: boolean;
  };
  circuit?: {
    backend?: string;
    processLocal?: boolean;
    destinationKey?: string;
    state?: string;
    consecutiveFailures?: number;
    failureThreshold?: number;
    openCooldownSeconds?: number;
    openedAt?: string | null;
    cooldownEndsAt?: string | null;
  };
};

/**
 * Rate-limit and delivery-breaker observation — current state on existing beans, not actuator meters.
 * Enter: Manifest {@code page.rate-limits} (admin.read).
 * 限额与投递开合观察页：读已有实现的当前状态，不是 actuator 指标。进入走 page.rate-limits。
 */
export default async function RateLimitsPage() {
  const { language, phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<ObservationDocument>("rate-limits"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_RATE_LIMITS, permissions.has("admin.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;

  const collected = body.collectedAt ? new Date(body.collectedAt) : null;
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });
  const formatTime = (value?: string | null) => {
    if (!value) return phrases.rateLimitsNotYetOpened;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? phrases.rateLimitsNotYetOpened : timeFormat.format(date);
  };

  const rate = body.rateLimit;
  const circuit = body.circuit;
  const windows = rate?.windows ?? [];
  const backendKey = rateLimitBackendPhraseKey(rate?.backend);
  const circuitBackendKey = rateLimitBackendPhraseKey(circuit?.backend);
  const stateKey = circuitStatePhraseKey(circuit?.state);
  const stateLabel = stateKey ? phrases[stateKey as keyof PhraseBook] : (circuit?.state ?? "");

  return (
    <section>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <PageHeading title={phrases.rateLimitsTitle} hint={phrases.rateLimitsHint} />
        <RateLimitsRefreshButton phrases={phrases} />
      </div>
      <p className="mt-2 text-xs text-muted">
        {collected && !Number.isNaN(collected.getTime())
          ? fillPhrase(phrases.rateLimitsCollectedAt, { at: timeFormat.format(collected) })
          : phrases.rateLimitsCollectedUnknown}
      </p>

      <div className="mt-6 grid gap-4 md:grid-cols-2">
        <Card title={phrases.rateLimitsWindowsTitle}>
          <Fact label={phrases.rateLimitsBackendLabel} value={phrases[backendKey as keyof PhraseBook]} />
          <p className="text-xs text-muted">
            {rate?.processLocal ? phrases.rateLimitsProcessLocalNote : phrases.rateLimitsSharedNote}
          </p>
          <Fact label={phrases.rateLimitsPermitsLabel} value={String(rate?.permits ?? "")} />
          <Fact
            label={phrases.rateLimitsWindowLabel}
            value={fillPhrase(phrases.rateLimitsWindowSeconds, { seconds: rate?.windowSeconds ?? 0 })}
          />
          <p className="text-xs text-muted">{phrases.rateLimitsRejectionNote}</p>
        </Card>

        <Card title={phrases.rateLimitsCircuitTitle}>
          <Fact label={phrases.rateLimitsBackendLabel} value={phrases[circuitBackendKey as keyof PhraseBook]} />
          <p className="text-xs text-muted">
            {circuit?.processLocal ? phrases.rateLimitsProcessLocalNote : phrases.rateLimitsSharedNote}
          </p>
          <Fact label={phrases.rateLimitsDestinationLabel} value={circuit?.destinationKey ?? ""} />
          <Fact label={phrases.rateLimitsCircuitStateLabel} value={stateLabel} />
          <Fact label={phrases.rateLimitsFailuresLabel} value={String(circuit?.consecutiveFailures ?? 0)} />
          <Fact label={phrases.rateLimitsThresholdLabel} value={String(circuit?.failureThreshold ?? "")} />
          <Fact
            label={phrases.rateLimitsCooldownLabel}
            value={fillPhrase(phrases.rateLimitsWindowSeconds, { seconds: circuit?.openCooldownSeconds ?? 0 })}
          />
          <Fact label={phrases.rateLimitsOpenedAtLabel} value={formatTime(circuit?.openedAt)} />
          <Fact label={phrases.rateLimitsCooldownEndsLabel} value={formatTime(circuit?.cooldownEndsAt)} />
        </Card>
      </div>

      {rate?.truncated ? <p className="mt-4 text-xs text-muted">{phrases.rateLimitsTruncated}</p> : null}

      {windows.length === 0 ? (
        <p className="mt-4 text-sm text-muted">{phrases.rateLimitsWindowsEmpty}</p>
      ) : (
        <table className="mt-4 w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsTenantColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsActionColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsUsedColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsRemainingColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsWindowStartColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.rateLimitsWindowEndColumn}</th>
            </tr>
          </thead>
          <tbody>
            {windows.map((row, index) => (
              <tr key={`${row.tenantId}:${row.actionName}:${index}`} className="border-t border-border">
                <td className="px-3 py-2 font-mono text-xs">{row.tenantId}</td>
                <td className="px-3 py-2 font-mono text-xs">{row.actionName}</td>
                <td className="px-3 py-2">{row.used}</td>
                <td className="px-3 py-2">{row.remaining}</td>
                <td className="whitespace-nowrap px-3 py-2">{formatTime(row.windowStartedAt)}</td>
                <td className="whitespace-nowrap px-3 py-2">{formatTime(row.windowEndsAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{title}</h2>
      <dl className="mt-3 space-y-2">{children}</dl>
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
