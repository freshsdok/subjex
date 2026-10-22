import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_FLAGS_WRITE,
  PAGE_FLAGS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { FlagsConsole } from "./flags-console";

/**
 * Feature flags page — list/edit tenant flags.
 * Enter: Manifest {@code page.flags} (config.read); write: {@code btn.flags.write} (config.write).
 * 功能开关页：进入走 page.flags；写入走 btn.flags.write。
 */
export default async function FlagsPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_FLAGS, permissions.has("config.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="config.read" />;
  }
  const canWrite = buttonAllowed(
    manifest,
    BUTTON_FLAGS_WRITE,
    permissions.has("config.write"),
  );
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.flagsTitle} hint={phrases.flagsHint} />
      <FlagsConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
