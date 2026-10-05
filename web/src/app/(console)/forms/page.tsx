import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { PublicationForm } from "./publication-form";

type FormsDocument = components["schemas"]["FormsDocument"];

// Forms page — 表单页：可编辑提交；缺 registry.write 时字段可看但置灰，并说明缺哪个权限。
export default async function FormsPage() {
  const { language, phrases } = await currentLanguage();
  const [formsRead, meRead] = await Promise.all([
    readPlatform<FormsDocument>("forms"),
    readPlatform<OperatorSelfDocument>("me"),
  ]);
  if (formsRead.status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!formsRead.body) return <LoadFailedNotice phrases={phrases} status={formsRead.status} />;
  const body = formsRead.body;
  const formTitle = language === "zh" ? body.titleZh : body.titleEn;
  const canWrite = (meRead.body?.permissions ?? []).includes("registry.write");
  return (
    <section>
      <PageHeading title={phrases.formsTitle} hint={phrases.formsHint} />
      <PublicationForm
        formKey={body.formKey ?? "endpoint-publication"}
        formTitle={formTitle ?? ""}
        fields={(body.fields ?? [])
          .filter((field): field is { name: string; kind: string; required: boolean } =>
            Boolean(field.name && field.kind && field.required !== undefined),
          )
          .map((field) => ({ name: field.name, kind: field.kind, required: field.required }))}
        canWrite={canWrite}
        phrases={phrases}
      />
    </section>
  );
}
