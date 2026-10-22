import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  BUTTON_CONFIG_WRITE,
  PAGE_CONFIG,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { ConfigNamespacePicker } from "./config-namespace-picker";
import { ConfigOverrideCell } from "./config-override-cell";

type ConfigDocument = components["schemas"]["ConfigDocument"];

const DEFAULT_NAMESPACE = "default";

function resolveNamespace(raw: string | undefined): string {
  const trimmed = (raw ?? "").trim();
  return trimmed === "" ? DEFAULT_NAMESPACE : trimmed;
}

/**
 * Config page — namespace picker (at least {@code default}) + override cells.
 * Enter: Manifest {@code page.config}; write column: {@code btn.config.write}.
 * Deep link: {@code /config?namespace=...}. Config-5d honesty notice + 5c If-Match on save.
 * <p>
 * 配置页：进入走 page.config；修改列走 btn.config.write。深链 ?namespace=。
 */
export default async function ConfigPage({
  searchParams,
}: {
  searchParams: Promise<{ namespace?: string }>;
}) {
  const { phrases } = await currentLanguage();
  const params = await searchParams;
  const namespace = resolveNamespace(params.namespace);
  const configPath =
    namespace === DEFAULT_NAMESPACE
      ? "config"
      : `config?namespace=${encodeURIComponent(namespace)}`;
  const [{ status, body }, { body: operator }, uiPermissionsRead] = await Promise.all([
    readPlatform<ConfigDocument>(configPath),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(operator?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_CONFIG, permissions.has("config.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="config.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="config.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const canOverride = buttonAllowed(
    manifest,
    BUTTON_CONFIG_WRITE,
    permissions.has("config.write"),
  );
  const activeNamespace = body.namespace?.trim() || namespace;
  const configEntries = body.entries ?? [];
  return (
    <section>
      <PageHeading title={phrases.configTitle} hint={phrases.configHint} />
      <p role="note" className="mb-4 max-w-3xl text-xs text-muted">
        {phrases.configHonestyNotice}
      </p>
      <ConfigNamespacePicker selected={activeNamespace} phrases={phrases} />
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
              <th className="px-3 py-2 font-medium">{phrases.configRevisionColumn}</th>
              {canOverride && <th className="px-3 py-2" />}
            </tr>
          </thead>
          <tbody>
            {configEntries.map((configEntry) => (
              <tr key={`${activeNamespace}:${configEntry.key}`} className="border-t border-border align-top">
                <td className="px-3 py-2 font-mono text-xs">{configEntry.key}</td>
                <td className="px-3 py-2 font-mono text-xs">{configEntry.value}</td>
                <td className="px-3 py-2 text-xs">
                  <span title={configEntry.origin}>
                    {configEntry.origin === "override" ? phrases.originOverride : phrases.originLocal}
                  </span>
                </td>
                <td className="px-3 py-2 font-mono text-xs">{configEntry.revision ?? 0}</td>
                {canOverride && (
                  <td className="w-96 px-3 py-2">
                    <ConfigOverrideCell
                      configKey={configEntry.key ?? ""}
                      currentValue={configEntry.value ?? ""}
                      revision={configEntry.revision ?? 0}
                      namespace={activeNamespace}
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
