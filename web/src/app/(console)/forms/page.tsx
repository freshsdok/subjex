import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform } from "@/server/platform-reader";

type FormsDocument = components["schemas"]["FormsDocument"];

// Forms page — 表单页：把字段定义渲染成只读预览，让人一眼看出表单长什么样、哪些必填。
export default async function FormsPage() {
  const { language, phrases } = await currentLanguage();
  const { status, body } = await readPlatform<FormsDocument>("forms");
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const formTitle = language === "zh" ? body.titleZh : body.titleEn;
  return (
    <section>
      <PageHeading title={phrases.formsTitle} hint={phrases.formsHint} />
      <fieldset disabled className="max-w-md rounded-lg border border-border bg-surface p-5">
        <legend className="px-1 font-semibold">{formTitle}</legend>
        <p className="mb-4 text-xs text-muted">
          {phrases.formKeyLabel}: <code>{body.formKey}</code>
        </p>
        <div className="flex flex-col gap-3">
          {(body.fields ?? []).map((field) => (
            <label key={field.name} className="flex flex-col gap-1 text-sm">
              <span>
                <code>{field.name}</code>
                <span className="ml-2 text-xs text-muted">
                  {field.kind === "integer" ? phrases.fieldKindInteger : phrases.fieldKindText}
                  {field.required ? ` · ${phrases.requiredMark}` : ""}
                </span>
              </span>
              <input
                type={field.kind === "integer" ? "number" : "text"}
                className="rounded-md border border-border bg-background px-2 py-1"
              />
            </label>
          ))}
        </div>
      </fieldset>
    </section>
  );
}
