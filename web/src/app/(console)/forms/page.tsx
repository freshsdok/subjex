import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  PAGE_FORMS,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { PublicationForm } from "./publication-form";
import { FormPicker } from "./form-picker";

type FormsIndexDocument = components["schemas"]["FormsIndexDocument"];
type FormsDocument = components["schemas"]["FormsDocument"];

/**
 * Forms page — index exposes declaration permissions; locked forms grey; detail by declared perm.
 * Enter: Manifest {@code page.forms} (page.read).
 * 表单页：进入走 page.forms；目录暴露声明权限；无权限置灰；详情按声明权限打开。
 */
export default async function FormsPage({
  searchParams,
}: {
  searchParams: Promise<{ form?: string }>;
}) {
  const { language, phrases } = await currentLanguage();
  const params = await searchParams;
  const [indexRead, meRead, uiPermissionsRead] = await Promise.all([
    readPlatform<FormsIndexDocument>("forms"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const myPermissions = new Set(meRead.body?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_FORMS, myPermissions.has("page.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  if (indexRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!indexRead.body?.forms?.length) {
    return <LoadFailedNotice phrases={phrases} status={indexRead.status || 500} />;
  }
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
          .filter((field): field is { name: string; kind: string; required: boolean; enumValues?: string[] } =>
            Boolean(field.name && field.kind && field.required !== undefined),
          )
          .map((field) => ({
            name: field.name,
            kind: field.kind,
            required: field.required,
            enumValues: Array.isArray(field.enumValues)
              ? field.enumValues.filter((v): v is string => typeof v === "string")
              : undefined,
          }))}
        canWrite={canWrite}
        writePermission={writePermission}
        phrases={phrases}
      />
    </section>
  );
}
