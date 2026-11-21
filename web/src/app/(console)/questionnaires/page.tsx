import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { orgTenantCookieName } from "@/lib/org-console";
import {
  BUTTON_QUESTIONNAIRES_WRITE,
  PAGE_QUESTIONNAIRES,
  buttonAllowed,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { QuestionnairesConsole } from "./questionnaires-console";

/**
 * Questionnaires page (Q6b) — thin authoring/stats home for platform questionnaire capability.
 * Enter: Manifest {@code page.questionnaires} (questionnaire.read);
 * write: {@code btn.questionnaires.write} (questionnaire.write).
 * Uni-app respondent fill is Q7 — not this page.
 * <p>
 * 问卷页（Q6b）：薄编排/统计；进入 questionnaire.read；写 questionnaire.write。答卷端属 Q7。
 */
export default async function QuestionnairesPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_QUESTIONNAIRES, permissions.has("questionnaire.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="questionnaire.read" />;
  }
  const canWrite = buttonAllowed(
    manifest,
    BUTTON_QUESTIONNAIRES_WRITE,
    permissions.has("questionnaire.write"),
  );
  const jar = await cookies();
  const tenantHint = (jar.get(orgTenantCookieName)?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.questionnairesTitle} hint={phrases.questionnairesHint} />
      <QuestionnairesConsole phrases={phrases} initialTenantId={tenantHint} canWrite={canWrite} />
    </section>
  );
}
