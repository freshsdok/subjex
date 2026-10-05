import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { PublicationForm } from "./publication-form";
import { FormPicker } from "./form-picker";

type FormsIndexDocument = components["schemas"]["FormsIndexDocument"];
type FormsDocument = components["schemas"]["FormsDocument"];

// Forms page — 表单页：目录暴露声明权限；无权限的表单置灰；详情按声明权限打开。
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
  const myPermissions = new Set(meRead.body?.permissions ?? []);
  const index = indexRead.body.forms
    .filter(
      (entry): entry is { formKey: string; titleZh: string; titleEn: string; permission?: string; tenantScoped?: boolean } =>
        Boolean(entry.formKey && entry.titleZh && entry.titleEn),
    )
    .map((entry) => ({
      ...entry,
      allowed: Boolean(entry.permission && myPermissions.has(entry.permission)),
    }));
  const requested = params.form;
  const preferred =
    (requested && index.find((entry) => entry.formKey === requested && entry.allowed)?.formKey) ||
    index.find((entry) => entry.allowed)?.formKey ||
    null;
  if (!preferred) {
    return (
      <section>
        <PageHeading title={phrases.formsTitle} hint={phrases.formsHint} />
        <FormPicker forms={index} selectedFormKey="" language={language} phrases={phrases} />
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      </section>
    );
  }
  const detailRead = await readPlatform<FormsDocument>(`forms/${encodeURIComponent(preferred)}`);
  if (detailRead.status === 403) {
    return (
      <ForbiddenNotice
        phrases={phrases}
        permission={index.find((entry) => entry.formKey === preferred)?.permission ?? "permission"}
      />
    );
  }
  if (!detailRead.body) return <LoadFailedNotice phrases={phrases} status={detailRead.status} />;
  const body = detailRead.body;
  const formTitle = language === "zh" ? body.titleZh : body.titleEn;
  const writePermission = body.permission ?? index.find((entry) => entry.formKey === preferred)?.permission ?? "";
  const canWrite = writePermission !== "" && myPermissions.has(writePermission);
  return (
    <section>
      <PageHeading title={phrases.formsTitle} hint={phrases.formsHint} />
      <FormPicker forms={index} selectedFormKey={preferred} language={language} phrases={phrases} />
      <PublicationForm
        formKey={body.formKey ?? preferred}
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
