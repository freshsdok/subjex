import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { CapabilityRow } from "@/lib/capabilities-console";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { CapabilitiesConsole } from "./capabilities-console";

type CapabilitiesDocument = {
  capabilities?: CapabilityRow[];
};

// Capabilities page — 能力页：page.read 可看目录并试跑桩（不经真网关、不写库）。
export default async function CapabilitiesPage() {
  const { language, phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("page.read")) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }

  const listed = await readPlatform<CapabilitiesDocument>("capabilities");
  if (listed.status === 403) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  if (!listed.body) {
    return <LoadFailedNotice phrases={phrases} status={listed.status} />;
  }

  const capabilities = (listed.body.capabilities ?? []).filter(
    (row): row is CapabilityRow => Boolean(row?.id),
  );

  return (
    <section>
      <PageHeading title={phrases.capabilitiesTitle} hint={phrases.capabilitiesHint} />
      <p
        role="note"
        className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted"
      >
        {phrases.capabilitiesStubNote}
      </p>
      <CapabilitiesConsole phrases={phrases} language={language} capabilities={capabilities} />
    </section>
  );
}
