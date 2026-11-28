import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_CONTENT_WRITE,
  PAGE_CONTENT,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { ContentConsole } from "./content-console";

/**
 * Public content pages — create, edit, publish, and unpublish structured pages.
 * Enter: Manifest {@code page.content} ({@code content.read});
 * write: {@code btn.content.write} ({@code content.write}).
 * Not the declaration flow-runtime at {@code /pages}.
 * <p>
 * 公开内容页：创建、编辑、发布或取消发布结构化页面。进入走 page.content（content.read）；
 * 写走 btn.content.write（content.write）。不是声明运行时 /pages。
 */
export default async function ContentPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_CONTENT, permissions.has("content.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="content.read" />;
  }
  const canWrite = buttonAllowed(manifest, BUTTON_CONTENT_WRITE, permissions.has("content.write"));
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.contentTitle} hint={phrases.contentHint} />
      <ContentConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
