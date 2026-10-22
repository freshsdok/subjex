import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  PAGE_CODEGEN,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type CodegenDocument = components["schemas"]["CodegenDocument"];

/**
 * Codegen page — record source preview + field/type table.
 * Enter: Manifest {@code page.codegen} (page.read).
 * 代码生成页：进入走 page.codegen。
 */
export default async function CodegenPage() {
  const { language, phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<CodegenDocument>("codegen"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_CODEGEN, permissions.has("page.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const recordComponents = body.components ?? [];
  const recordSource = `public record ${body.recordName}(${recordComponents
    .map((recordComponent) => `${recordComponent.javaType} ${recordComponent.name}`)
    .join(", ")}) {}`;
  return (
    <section>
      <PageHeading title={phrases.codegenTitle} hint={phrases.codegenHint} />
      <pre className="mb-5 max-w-3xl overflow-x-auto rounded-lg border border-border bg-surface p-4 font-mono text-xs">
        {recordSource}
      </pre>
      <table className="w-full max-w-3xl border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
        <thead className="bg-background text-left text-muted">
          <tr>
            <th className="px-3 py-2 font-medium">{phrases.fieldColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.javaTypeColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.typeMeaningColumn}</th>
          </tr>
        </thead>
        <tbody>
          {recordComponents.map((recordComponent) => (
            <tr key={recordComponent.name} className="border-t border-border">
              <td className="px-3 py-2 font-mono text-xs">{recordComponent.name}</td>
              <td className="px-3 py-2 font-mono text-xs">{recordComponent.javaType}</td>
              <td className="px-3 py-2 text-xs">{language === "zh" ? recordComponent.typeZh : recordComponent.javaType}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

