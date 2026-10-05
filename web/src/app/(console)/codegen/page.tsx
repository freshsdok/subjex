import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform } from "@/server/platform-reader";

type CodegenDocument = components["schemas"]["CodegenDocument"];

// Codegen page — 代码生成页：先给出生成的 record 源码预览，再列字段与类型对照。
export default async function CodegenPage() {
  const { language, phrases } = await currentLanguage();
  const { status, body } = await readPlatform<CodegenDocument>("codegen");
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
