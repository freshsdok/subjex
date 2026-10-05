import Link from "next/link";
import type { ReactNode } from "react";
import type { components } from "@/api/schema";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type ServiceListDocument = components["schemas"]["ServiceListDocument"];
type AuditDocument = components["schemas"]["AuditDocument"];
type DeployDocument = components["schemas"]["DeployDocument"];

const recentAuditLimit = 5;

// One overview section — 概览中的一个分区：标题、“查看全部”链接，以及各自独立的权限/失败提示。
function OverviewSection({
  title,
  detailHref,
  phrases,
  children,
}: {
  title: string;
  detailHref?: string;
  phrases: PhraseBook;
  children: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface p-4">
      <header className="mb-3 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold">{title}</h2>
        {detailHref ? (
          <Link href={detailHref} className="text-xs text-accent hover:underline">
            {phrases.viewAll}
          </Link>
        ) : null}
      </header>
      {children}
    </section>
  );
}

// A section that could not load explains why, without breaking the rest of the page.
// 某一块读不到时只在这一块里说明原因（缺权限或失败），不影响其他分区。
function SectionProblem({ phrases, status, permission }: { phrases: PhraseBook; status: number; permission: string }) {
  return status === 403 ? (
    <p role="status" className="text-sm text-muted">
      {fillPhrase(phrases.sectionForbidden, { permission })}
    </p>
  ) : (
    <p role="alert" className="text-sm text-danger">
      {fillPhrase(phrases.sectionLoadFailed, { status })}
    </p>
  );
}

// Overview page — 概览页：服务健康、最近审计、部署提示分区并排，最后是“我能做什么”。
export default async function OverviewPage() {
  const { language, phrases } = await currentLanguage();
  const [operatorRead, servicesRead, auditRead, deployRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<ServiceListDocument>("services"),
    readPlatform<AuditDocument>("audit"),
    readPlatform<DeployDocument>("deploy"),
  ]);
  const permissions = operatorRead.body?.permissions ?? [];
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "short",
    timeStyle: "medium",
  });

  const services = servicesRead.body?.services ?? [];
  const upCount = services.filter((service) => service.status === "up").length;
  const servicesNeedingAttention = services.filter((service) => service.status !== "up");

  const recentAuditEntries = [...(auditRead.body?.entries ?? [])]
    .sort((left, right) => (right.occurredAt ?? "").localeCompare(left.occurredAt ?? ""))
    .slice(0, recentAuditLimit);

  return (
    <div className="max-w-5xl">
      <h1 className="mb-4 text-xl font-semibold">{phrases.overviewTitle}</h1>
      <div className="mb-6 grid gap-4 md:grid-cols-2">
        <OverviewSection title={phrases.overviewHealthTitle} detailHref="/services" phrases={phrases}>
          {!servicesRead.body ? (
            <SectionProblem phrases={phrases} status={servicesRead.status} permission="registry.read" />
          ) : services.length === 0 ? (
            <p className="text-sm text-muted">{phrases.emptyList}</p>
          ) : (
            <>
              <p className="text-sm">
                {fillPhrase(phrases.overviewHealthSummary, {
                  total: services.length,
                  up: upCount,
                  other: servicesNeedingAttention.length,
                })}
              </p>
              {servicesNeedingAttention.length > 0 ? (
                <ul className="mt-2 space-y-1 text-sm">
                  {servicesNeedingAttention.map((service) => (
                    <li key={`${service.serviceName}@${service.host}:${service.port}`} className="text-danger">
                      {service.serviceName} <span className="font-mono text-xs">{service.host}:{service.port}</span> · {service.status}
                    </li>
                  ))}
                </ul>
              ) : null}
            </>
          )}
        </OverviewSection>

        <OverviewSection title={phrases.overviewDeployTitle} detailHref="/deploy" phrases={phrases}>
          {!deployRead.body ? (
            <SectionProblem phrases={phrases} status={deployRead.status} permission="page.read" />
          ) : deployRead.body.applied ? (
            <p className="text-sm">{phrases.deployApplied}</p>
          ) : (
            <p role="status" className="text-sm">
              {fillPhrase(phrases.overviewDeployNotAppliedCount, { count: deployRead.body.workloads?.length ?? 0 })}
            </p>
          )}
        </OverviewSection>

        <div className="md:col-span-2">
          <OverviewSection title={phrases.overviewAuditTitle} detailHref="/audit" phrases={phrases}>
            {!auditRead.body ? (
              <SectionProblem phrases={phrases} status={auditRead.status} permission="admin.read" />
            ) : recentAuditEntries.length === 0 ? (
              <p className="text-sm text-muted">{phrases.emptyList}</p>
            ) : (
              <ul className="divide-y divide-border text-sm">
                {recentAuditEntries.map((auditEntry) => (
                  <li key={auditEntry.auditEntryId} className="flex flex-wrap gap-x-3 py-1.5">
                    <span className="whitespace-nowrap text-muted">
                      {auditEntry.occurredAt ? timeFormat.format(new Date(auditEntry.occurredAt)) : ""}
                    </span>
                    <span>{auditEntry.actorLogin ?? auditEntry.actor}</span>
                    <span title={auditEntry.actionName}>
                      {language === "zh" ? auditEntry.actionWordZh : auditEntry.actionWordEn}
                    </span>
                    <span className="font-mono text-xs">{auditEntry.actionTarget}</span>
                    <span className="text-muted">
                      {language === "zh" ? auditEntry.outcomeWordZh : auditEntry.outcomeWordEn}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </OverviewSection>
        </div>
      </div>

      <h2 className="mb-2 text-sm font-medium text-muted">{phrases.yourPermissions}</h2>
      {permissions.length === 0 ? (
        <p className="text-sm">{phrases.noPermissions}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {permissions.map((permissionName) => (
            <li key={permissionName} className="rounded-full border border-border bg-surface px-3 py-1 font-mono text-xs">
              {permissionName}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
