import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_POINTS_ADJUST,
  PAGE_POINTS,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { PointsConsole } from "./points-console";

/**
 * Points ledger — look up a subject in a tenant and grant or deduct with a reason.
 * Enter: Manifest {@code page.points} ({@code points.read});
 * write: {@code btn.points.adjust} ({@code points.adjust}). Not a loyalty program.
 * <p>
 * 积分台账：在租户内查阅主体并按原因发放或扣减。进入走 page.points（points.read）；
 * 写走 btn.points.adjust（points.adjust）。不是忠诚计划。
 */
export default async function PointsPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_POINTS, permissions.has("points.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="points.read" />;
  }
  const canWrite = buttonAllowed(manifest, BUTTON_POINTS_ADJUST, permissions.has("points.adjust"));
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.pointsTitle} hint={phrases.pointsHint} />
      <PointsConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
