import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_OBJECTS_GRANT,
  PAGE_OBJECTS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { ObjectsConsole } from "./objects-console";

/**
 * Object grants — list registered objects and grant or revoke access (cut 2).
 * Enter: Manifest {@code page.objects} ({@code object.grant.read});
 * write: {@code btn.objects.grant} ({@code object.grant}). Not a public share product.
 * <p>
 * 对象授权：列出已登记对象并授予或撤销访问。进入走 page.objects（object.grant.read）；
 * 写走 btn.objects.grant（object.grant）。不是公开分享产品。
 */
export default async function ObjectsPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (
    !pageAllowed(
      manifest,
      PAGE_OBJECTS,
      permissions.has("object.grant.read") || permissions.has("object.grant"),
    )
  ) {
    return <ForbiddenNotice phrases={phrases} permission="object.grant.read" />;
  }
  const canWrite = buttonAllowed(manifest, BUTTON_OBJECTS_GRANT, permissions.has("object.grant"));
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.objectsTitle} hint={phrases.objectsHint} />
      <ObjectsConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
