import Link from "next/link";
import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { platformPathFromApi } from "@/lib/page-flow";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { DeclaredSubmitForm } from "./submit-form";

type PageFlowDocument = {
  flowKey?: string;
  titleZh?: string;
  titleEn?: string;
  formKey?: string | null;
  permission?: string;
  tenantScoped?: boolean;
  submit?: { apiPath?: string; redirectTo?: string };
};

type FormsDocument = {
  formKey?: string;
  permission?: string;
  fields?: { name?: string; kind?: string; required?: boolean }[];
};

// Declared submit page — 声明式提交页：按 formKey 拉字段；写权限取自表单声明，不再按键硬编码。
export default async function DeclaredSubmitPage({
  params,
}: {
  params: Promise<{ flowKey: string }>;
}) {
  const { flowKey } = await params;
  const { language, phrases } = await currentLanguage();
  const [flowRead, meRead] = await Promise.all([
    readPlatform<PageFlowDocument>(`pages/${encodeURIComponent(flowKey)}`),
    readPlatform<OperatorSelfDocument>("me"),
  ]);
  if (flowRead.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  if (!flowRead.body?.submit?.apiPath || !flowRead.body.submit.redirectTo) {
    return <LoadFailedNotice phrases={phrases} status={flowRead.status || 404} />;
  }
  const flow = flowRead.body;
  const formKey = flow.formKey;
  if (!formKey) {
    return (
      <section>
        <PageHeading title={phrases.pagesTitle} hint={phrases.pagesSubmitNoFormHint} />
        <Link href={`/pages/${encodeURIComponent(flowKey)}`} className="text-sm text-accent underline">
          {phrases.backToListAction}
        </Link>
      </section>
    );
  }
  const formRead = await readPlatform<FormsDocument>(`forms/${encodeURIComponent(formKey)}`);
  if (formRead.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="permission" />;
  }
  if (!formRead.body?.fields) {
    return <LoadFailedNotice phrases={phrases} status={formRead.status || 404} />;
  }
  const fields = formRead.body.fields
    .filter((field): field is { name: string; kind: string; required: boolean } =>
      Boolean(field.name && field.kind && field.required !== undefined),
    )
    .map((field) => ({ name: field.name, kind: field.kind, required: field.required }));
  const writePermission = formRead.body.permission ?? "";
  const canWrite = writePermission !== "" && (meRead.body?.permissions ?? []).includes(writePermission);
  const title = language === "zh" ? flow.titleZh : flow.titleEn;
  return (
    <section>
      <PageHeading title={title ?? flowKey} hint={phrases.pagesSubmitHint} />
      <p className="mb-4 text-sm">
        <Link href={`/pages/${encodeURIComponent(flowKey)}`} className="text-accent underline">
          {phrases.backToListAction}
        </Link>
      </p>
      <DeclaredSubmitForm
        formKey={formKey}
        formTitle={title ?? formKey}
        fields={fields}
        submitApiPath={platformPathFromApi(flow.submit!.apiPath!)}
        redirectTo={flow.submit!.redirectTo!}
        canWrite={canWrite}
        writePermission={writePermission}
        phrases={phrases}
      />
    </section>
  );
}
