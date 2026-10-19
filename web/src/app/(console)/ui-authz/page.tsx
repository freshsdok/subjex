import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { UiAuthzEditor } from "./ui-authz-editor";

/**
 * UI permission Manifest editor (ADR 0010 / PC-6a / PC-6b).
 * Gates: declaration.read enter; declaration.write save; declaration.promote promote.
 * 进入需 declaration.read；保存需 declaration.write；晋升需 declaration.promote。
 */
export default async function UiAuthzPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("declaration.read")) {
    return <ForbiddenNotice phrases={phrases} permission="declaration.read" />;
  }
  const canWrite = permissions.has("declaration.write");
  const canPromote = permissions.has("declaration.promote");
  return (
    <section>
      <PageHeading title={phrases.uiAuthzTitle} hint={phrases.uiAuthzHint} />
      <UiAuthzEditor phrases={phrases} canWrite={canWrite} canPromote={canPromote} />
    </section>
  );
}
