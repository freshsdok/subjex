import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  BUTTON_UI_AUTHZ_PROMOTE,
  BUTTON_UI_AUTHZ_WRITE,
  PAGE_UI_AUTHZ,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { UiAuthzEditor } from "./ui-authz-editor";

/**
 * UI permission Manifest editor (ADR 0010 / PC-6 / #5 deepen).
 * Enter: {@code page.ui-authz}; save {@code btn.ui-authz.write}; promote {@code btn.ui-authz.promote}.
 * 进入 page.ui-authz；保存/晋升走 Manifest 按钮。
 */
export default async function UiAuthzPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_UI_AUTHZ, permissions.has("declaration.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="declaration.read" />;
  }
  const canWrite = buttonAllowed(
    manifest,
    BUTTON_UI_AUTHZ_WRITE,
    permissions.has("declaration.write"),
  );
  const canPromote = buttonAllowed(
    manifest,
    BUTTON_UI_AUTHZ_PROMOTE,
    permissions.has("declaration.promote"),
  );
  return (
    <section>
      <PageHeading title={phrases.uiAuthzTitle} hint={phrases.uiAuthzHint} />
      <UiAuthzEditor phrases={phrases} canWrite={canWrite} canPromote={canPromote} />
    </section>
  );
}
