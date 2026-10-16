import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform } from "@/server/platform-reader";
import { DeadLetterActions } from "./dead-letter-actions";

type DeadLettersDocument = {
  deadLetters?: Array<{
    deadLetterId?: string;
    tenantId?: string;
    originKind?: string;
    originId?: string;
    failureReason?: string;
    recordedAt?: string;
  }>;
};

/**
 * Dead letters console — list + replay/discard (admin.read).
 * 死信控制台：列表与重放/丢弃。
 */
export default async function DeadLettersPage() {
  const { phrases } = await currentLanguage();
  const { status, body } = await readPlatform<DeadLettersDocument>("dead-letters");
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const letters = (body.deadLetters ?? [])
    .filter((l) => (l.deadLetterId ?? "").trim())
    .map((l) => ({
      deadLetterId: (l.deadLetterId ?? "").trim(),
      tenantId: (l.tenantId ?? "").trim(),
      originKind: (l.originKind ?? "").trim(),
      originId: (l.originId ?? "").trim(),
      failureReason: (l.failureReason ?? "").trim(),
      recordedAt: l.recordedAt,
    }));
  return (
    <section>
      <PageHeading title={phrases.deadLettersTitle} hint={phrases.deadLettersHint} />
      <DeadLetterActions phrases={phrases} letters={letters} />
    </section>
  );
}
