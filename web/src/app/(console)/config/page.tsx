import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { ConfigOverrideCell } from "./config-override-cell";

type ConfigDocument = components["schemas"]["ConfigDocument"];

// Config page — 配置页：所有人看到同一张表；有 config.write 才出现“修改”列，否则顶部说明为什么只读。
export default async function ConfigPage() {
  const { phrases } = await currentLanguage();
  const [{ status, body }, { body: operator }] = await Promise.all([
    readPlatform<ConfigDocument>("config"),
    readPlatform<OperatorSelfDocument>("me"),
  ]);
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="config.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const canOverride = (operator?.permissions ?? []).includes("config.write");
  const configEntries = body.entries ?? [];
  return (
    <section>
      <PageHeading title={phrases.configTitle} hint={phrases.configHint} />
      {!canOverride && (
        <p role="status" className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
          {phrases.configReadOnlyNotice}
        </p>
      )}
      {configEntries.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.configKeyColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.configValueColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.configOriginColumn}</th>
              {canOverride && <th className="px-3 py-2" />}
            </tr>
          </thead>
          <tbody>
            {configEntries.map((configEntry) => (
              <tr key={configEntry.key} className="border-t border-border align-top">
                <td className="px-3 py-2 font-mono text-xs">{configEntry.key}</td>
                <td className="px-3 py-2 font-mono text-xs">{configEntry.value}</td>
                <td className="px-3 py-2 text-xs">
                  <span title={configEntry.origin}>
                    {configEntry.origin === "override" ? phrases.originOverride : phrases.originLocal}
                  </span>
                </td>
                {canOverride && (
                  <td className="w-96 px-3 py-2">
                    <ConfigOverrideCell
                      configKey={configEntry.key ?? ""}
                      currentValue={configEntry.value ?? ""}
                      phrases={phrases}
                    />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
