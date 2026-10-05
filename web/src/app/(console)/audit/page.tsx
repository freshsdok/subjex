import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform } from "@/server/platform-reader";

type AuditDocument = components["schemas"]["AuditDocument"];

// Audit page — 审计页：按时间倒序展示，动作和结果用当前语言的词，原始动作名放在悬停提示里便于排查。
export default async function AuditPage() {
  const { language, phrases } = await currentLanguage();
  const { status, body } = await readPlatform<AuditDocument>("audit");
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const auditEntries = [...(body.entries ?? [])].sort((left, right) =>
    (right.occurredAt ?? "").localeCompare(left.occurredAt ?? ""),
  );
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });
  return (
    <section>
      <PageHeading title={phrases.auditTitle} hint={phrases.auditHint} />
      {auditEntries.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.occurredAtColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.actorColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.actionColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.targetColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.outcomeColumn}</th>
            </tr>
          </thead>
          <tbody>
            {auditEntries.map((auditEntry) => (
              <tr key={auditEntry.auditEntryId} className="border-t border-border">
                <td className="whitespace-nowrap px-3 py-2">
                  {auditEntry.occurredAt ? timeFormat.format(new Date(auditEntry.occurredAt)) : ""}
                </td>
                <td className="px-3 py-2">{auditEntry.actorLogin ?? auditEntry.actor}</td>
                <td className="px-3 py-2" title={auditEntry.actionName}>
                  {language === "zh" ? auditEntry.actionWordZh : auditEntry.actionWordEn}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{auditEntry.actionTarget}</td>
                <td className="px-3 py-2">
                  {language === "zh" ? auditEntry.outcomeWordZh : auditEntry.outcomeWordEn}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
