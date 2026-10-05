import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { PublicationForm } from "./publication-form";
import { FormPicker } from "./form-picker";

type FormsIndexDocument = components["schemas"]["FormsIndexDocument"];
type FormsDocument = components["schemas"]["FormsDocument"];

/** Write permission each form needs — 每张表单提交所需的写权限。 */
function writePermissionFor(formKey: string): string {
  if (formKey === "config-override") return "config.write";
  return "registry.write";
}

// Forms page — 表单页：先选表单，再编辑提交；写权限随表单键变化。
export default async function FormsPage({
  searchParams,
}: {
  searchParams: Promise<{ form?: string }>;
}) {
  const { language, phrases } = await currentLanguage();
  const params = await searchParams;
  const [indexRead, meRead] = await Promise.all([
    readPlatform<FormsIndexDocument>("forms"),
    readPlatform<OperatorSelfDocument>("me"),
  ]);
  if (indexRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!indexRead.body?.forms?.length) {
    return <LoadFailedNotice phrases={phrases} status={indexRead.status || 500} />;
  }
  const index = indexRead.body.forms.filter(
    (entry): entry is { formKey: string; titleZh: string; titleEn: string } =>
      Boolean(entry.formKey && entry.titleZh && entry.titleEn),
  );
  const requested = params.form;
  const formKey =
    (requested && index.some((entry) => entry.formKey === requested) ? requested : null) ??
    index[0].formKey;
  const detailRead = await readPlatform<FormsDocument>(`forms/${encodeURIComponent(formKey)}`);
  if (detailRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!detailRead.body) return <LoadFailedNotice phrases={phrases} status={detailRead.status} />;
  const body = detailRead.body;
  const formTitle = language === "zh" ? body.titleZh : body.titleEn;
  const writePermission = writePermissionFor(formKey);
  const canWrite = (meRead.body?.permissions ?? []).includes(writePermission);
  return (
    <section>
      <PageHeading title={phrases.formsTitle} hint={phrases.formsHint} />
      <FormPicker forms={index} selectedFormKey={formKey} language={language} phrases={phrases} />
      <PublicationForm
        formKey={body.formKey ?? formKey}
        formTitle={formTitle ?? ""}
        fields={(body.fields ?? [])
          .filter((field): field is { name: string; kind: string; required: boolean } =>
            Boolean(field.name && field.kind && field.required !== undefined),
          )
          .map((field) => ({ name: field.name, kind: field.kind, required: field.required }))}
        canWrite={canWrite}
        writePermission={writePermission}
        phrases={phrases}
      />
    </section>
  );
}
