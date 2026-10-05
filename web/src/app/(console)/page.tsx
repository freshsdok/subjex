import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

// Overview page — 概览页：先展示“我是谁、我能做什么”，后续再加服务与任务摘要。
export default async function OverviewPage() {
  const { phrases } = await currentLanguage();
  const { body: operator } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = operator?.permissions ?? [];
  return (
    <section className="max-w-2xl">
      <h1 className="mb-4 text-xl font-semibold">{phrases.overviewTitle}</h1>
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
    </section>
  );
}
