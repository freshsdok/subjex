import { PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { ConsentPanel } from "./consent-panel";

/**
 * OAuth consent page — ID-3 minimal UI for third-party authorize challenges.
 * OAuth 同意页：第三方授权挑战的最小界面。
 */
export default async function OauthConsentPage({
  searchParams,
}: {
  searchParams: Promise<{ challenge_id?: string }>;
}) {
  const { phrases, language } = await currentLanguage();
  const params = await searchParams;
  const challengeId = params.challenge_id?.trim() ?? "";
  const zh = language.toLowerCase().startsWith("zh");

  return (
    <section>
      <PageHeading
        title={zh ? "授权同意" : "Authorize application"}
        hint={zh ? "确认第三方应用可访问的范围" : "Confirm scopes for a third-party application"}
      />
      {!challengeId ? (
        <p className="mt-4 text-sm text-muted">
          {zh ? "缺少 challenge_id 参数" : "Missing challenge_id query parameter"}
        </p>
      ) : (
        <ConsentPanel challengeId={challengeId} lang={language} />
      )}
      {/* phrases keep PageHeading typed; unused label reserved for later i18n keys */}
      <span className="hidden">{phrases.emptyList}</span>
    </section>
  );
}
