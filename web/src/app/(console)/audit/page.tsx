import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  PAGE_AUDIT,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type AuditDocument = components["schemas"]["AuditDocument"];

/**
 * Audit page — newest-first list; action/outcome words follow console language;
 * raw {@code actionName} in title for operators debugging.
 * Enter: Manifest {@code page.audit} (admin.read). Deep link from overview → {@code /audit}.
 * <p>
 * 审计页：进入走 page.audit。时间倒序；动作/结果跟当前语言；原始动作名悬停排查。
 */
export default async function AuditPage() {
  const { language, phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<AuditDocument>("audit"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_AUDIT, permissions.has("admin.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
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
