import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_SCHEDULES_WRITE,
  PAGE_SCHEDULES,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { SchedulesConsole } from "./schedules-console";

/**
 * Operator schedules — create, list, enable, and disable cron work.
 * Enter: Manifest {@code page.schedules} ({@code schedule.read});
 * write: {@code btn.schedules.write} ({@code schedule.write}).
 * <p>
 * 操作员调度：创建、列出、启用或停用 cron 工作。进入走 page.schedules（schedule.read）；
 * 写走 btn.schedules.write（schedule.write）。
 */
export default async function SchedulesPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_SCHEDULES, permissions.has("schedule.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="schedule.read" />;
  }
  const canWrite = buttonAllowed(manifest, BUTTON_SCHEDULES_WRITE, permissions.has("schedule.write"));
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.schedulesTitle} hint={phrases.schedulesHint} />
      <SchedulesConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
