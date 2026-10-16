import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { FlagsConsole } from "./flags-console";

/**
 * Feature flags page — list/edit tenant flags (config.read / config.write).
 * 功能开关页：列/改租户开关。
 */
export default async function FlagsPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("config.read")) {
    return <ForbiddenNotice phrases={phrases} permission="config.read" />;
  }
  const canWrite = permissions.has("config.write");
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.flagsTitle} hint={phrases.flagsHint} />
      <FlagsConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
