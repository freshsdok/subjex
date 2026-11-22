import type { ReactNode } from "react";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  PAGE_LOAD,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import {
  formatBytes,
  formatCount,
  formatDurationSeconds,
  formatPercent,
  healthPhraseKey,
} from "@/lib/load-dashboard";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { LoadRefreshButton } from "./load-refresh-button";

type LoadDocument = {
  collectedAt?: string;
  liveness?: string;
  readiness?: string;
  process?: {
    uptimeSeconds?: number | null;
    cpuUsage?: number | null;
    startedAt?: string | null;
  };
  jvm?: {
    heapUsedBytes?: number | null;
    heapMaxBytes?: number | null;
    nonHeapUsedBytes?: number | null;
    threadsLive?: number | null;
    threadsPeak?: number | null;
    gcPauseCount?: number | null;
    gcPauseSeconds?: number | null;
  };
  http?: {
    present?: boolean;
    requestCount?: number | null;
    totalTimeSeconds?: number | null;
    maxSeconds?: number | null;
    byStatus?: Array<{ status?: string; count?: number }>;
  };
  jdbcPool?: {
    present?: boolean;
    active?: number | null;
    idle?: number | null;
    pending?: number | null;
    max?: number | null;
  };
  tomcat?: {
    present?: boolean;
    busy?: number | null;
    current?: number | null;
    max?: number | null;
  };
  log?: {
    present?: boolean;
    byLevel?: Array<{ level?: string; count?: number }>;
  };
  rateLimitMetricsPresent?: boolean;
  circuitBreakerMetricsPresent?: boolean;
};

/**
 * Load dashboard — stock Micrometer meters from the connected platform process.
 * Enter: Manifest {@code page.load} (admin.read).
 * 负载页：已连接平台进程里的现成 Micrometer 指标。进入走 page.load。
 */
export default async function LoadPage() {
  const { language, phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<LoadDocument>("load"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_LOAD, permissions.has("admin.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;

  const collected = body.collectedAt ? new Date(body.collectedAt) : null;
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });

  return (
    <section>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <PageHeading title={phrases.loadTitle} hint={phrases.loadHint} />
        <LoadRefreshButton phrases={phrases} />
      </div>
      <p className="mt-2 text-xs text-muted">
        {collected && !Number.isNaN(collected.getTime())
          ? fillPhrase(phrases.loadCollectedAt, { at: timeFormat.format(collected) })
          : phrases.loadCollectedUnknown}
      </p>

      <div className="mt-6 grid gap-4 md:grid-cols-2">
        <LoadCard title={phrases.loadHealthTitle}>
          <LoadFact
            label={phrases.loadLivenessLabel}
            value={healthLabel(body.liveness, phrases)}
          />
          <LoadFact
            label={phrases.loadReadinessLabel}
            value={healthLabel(body.readiness, phrases)}
          />
        </LoadCard>

        <LoadCard title={phrases.loadProcessTitle}>
          <LoadFact
            label={phrases.loadUptimeLabel}
            value={formatDurationSeconds(body.process?.uptimeSeconds ?? null, language)}
            missing={phrases.loadMissing}
          />
          <LoadFact
            label={phrases.loadCpuLabel}
            value={formatPercent(body.process?.cpuUsage ?? null, language)}
            missing={phrases.loadMissing}
          />
          <LoadFact
            label={phrases.loadStartedLabel}
            value={
              body.process?.startedAt && !Number.isNaN(Date.parse(body.process.startedAt))
                ? timeFormat.format(new Date(body.process.startedAt))
                : null
            }
            missing={phrases.loadMissing}
          />
        </LoadCard>

        <LoadCard title={phrases.loadJvmTitle}>
          <LoadFact
            label={phrases.loadHeapLabel}
            value={heapLine(body.jvm, language, phrases)}
            missing={phrases.loadMissing}
          />
          <LoadFact
            label={phrases.loadNonHeapLabel}
            value={formatBytes(body.jvm?.nonHeapUsedBytes ?? null, language)}
            missing={phrases.loadMissing}
          />
          <LoadFact
            label={phrases.loadThreadsLabel}
            value={threadsLine(body.jvm, language, phrases)}
            missing={phrases.loadMissing}
          />
          <LoadFact
            label={phrases.loadGcLabel}
            value={gcLine(body.jvm, language, phrases)}
            missing={phrases.loadMissing}
          />
        </LoadCard>

        <LoadCard title={phrases.loadHttpTitle} hint={phrases.loadCumulativeHint}>
          {body.http?.present ? (
            <>
              <LoadFact
                label={phrases.loadHttpCountLabel}
                value={formatCount(body.http.requestCount ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadHttpTotalTimeLabel}
                value={formatDurationSeconds(body.http.totalTimeSeconds ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadHttpMaxLabel}
                value={
                  body.http.maxSeconds != null
                    ? `${new Intl.NumberFormat(language === "zh" ? "zh-CN" : "en", { maximumFractionDigits: 3 }).format(body.http.maxSeconds)} s`
                    : null
                }
                missing={phrases.loadMissing}
              />
              {(body.http.byStatus ?? []).length > 0 ? (
                <ul className="mt-2 space-y-1 text-sm">
                  {(body.http.byStatus ?? []).map((row) => (
                    <li key={row.status ?? "unknown"}>
                      {fillPhrase(phrases.loadHttpStatusLine, {
                        status: row.status ?? "—",
                        count: formatCount(row.count ?? 0, language) ?? "0",
                      })}
                    </li>
                  ))}
                </ul>
              ) : null}
            </>
          ) : (
            <p className="text-sm text-muted">{phrases.loadHttpMissing}</p>
          )}
        </LoadCard>

        <LoadCard title={phrases.loadPoolTitle}>
          {body.jdbcPool?.present ? (
            <>
              <LoadFact
                label={phrases.loadPoolActiveLabel}
                value={formatCount(body.jdbcPool.active ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadPoolIdleLabel}
                value={formatCount(body.jdbcPool.idle ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadPoolPendingLabel}
                value={formatCount(body.jdbcPool.pending ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadPoolMaxLabel}
                value={formatCount(body.jdbcPool.max ?? null, language)}
                missing={phrases.loadMissing}
              />
            </>
          ) : (
            <p className="text-sm text-muted">{phrases.loadPoolMissing}</p>
          )}
        </LoadCard>

        <LoadCard title={phrases.loadTomcatTitle}>
          {body.tomcat?.present ? (
            <>
              <LoadFact
                label={phrases.loadTomcatBusyLabel}
                value={formatCount(body.tomcat.busy ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadTomcatCurrentLabel}
                value={formatCount(body.tomcat.current ?? null, language)}
                missing={phrases.loadMissing}
              />
              <LoadFact
                label={phrases.loadTomcatMaxLabel}
                value={formatCount(body.tomcat.max ?? null, language)}
                missing={phrases.loadMissing}
              />
            </>
          ) : (
            <p className="text-sm text-muted">{phrases.loadTomcatMissing}</p>
          )}
        </LoadCard>

        <LoadCard title={phrases.loadLogTitle} hint={phrases.loadCumulativeHint}>
          {body.log?.present ? (
            <ul className="space-y-1 text-sm">
              {(body.log.byLevel ?? []).map((row) => (
                <li key={row.level ?? "unknown"}>
                  {fillPhrase(phrases.loadLogLevelLine, {
                    level: row.level ?? "—",
                    count: formatCount(row.count ?? 0, language) ?? "0",
                  })}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted">{phrases.loadLogMissing}</p>
          )}
        </LoadCard>
      </div>

      <p className="mt-6 text-xs text-muted">{phrases.loadRateLimitNote}</p>
    </section>
  );
}

function LoadCard({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{title}</h2>
      {hint ? <p className="mt-1 text-xs text-muted">{hint}</p> : null}
      <div className="mt-3 space-y-2">{children}</div>
    </section>
  );
}

function LoadFact({
  label,
  value,
  missing,
}: {
  label: string;
  value: string | null | undefined;
  missing?: string;
}) {
  return (
    <p className="text-sm">
      <span className="text-muted">{label}</span>
      <span className="ml-2">{value || missing || "—"}</span>
    </p>
  );
}

function healthLabel(state: string | undefined, phrases: PhraseBook): string {
  const key = healthPhraseKey(state);
  if (key) return phrases[key];
  return state?.trim() || phrases.loadMissing;
}

function heapLine(
  jvm: LoadDocument["jvm"],
  language: string,
  phrases: PhraseBook,
): string | null {
  const used = formatBytes(jvm?.heapUsedBytes ?? null, language);
  const max = formatBytes(jvm?.heapMaxBytes ?? null, language);
  if (used && max) return fillPhrase(phrases.loadHeapUsedOfMax, { used, max });
  return used;
}

function threadsLine(
  jvm: LoadDocument["jvm"],
  language: string,
  phrases: PhraseBook,
): string | null {
  const live = formatCount(jvm?.threadsLive ?? null, language);
  const peak = formatCount(jvm?.threadsPeak ?? null, language);
  if (live && peak) return fillPhrase(phrases.loadThreadsLiveOfPeak, { live, peak });
  return live;
}

function gcLine(
  jvm: LoadDocument["jvm"],
  language: string,
  phrases: PhraseBook,
): string | null {
  const count = formatCount(jvm?.gcPauseCount ?? null, language);
  const time = formatDurationSeconds(jvm?.gcPauseSeconds ?? null, language);
  if (count && time) return fillPhrase(phrases.loadGcCountAndTime, { count, time });
  return count ?? time;
}
