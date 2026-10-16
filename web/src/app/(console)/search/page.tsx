import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { SearchConsole } from "./search-console";

/**
 * Search page — read-only entity_record search (page.read).
 * 搜索页：只读 entity_record（page.read）。
 */
export default async function SearchPage() {
  const { phrases } = await currentLanguage();
  const { body: me } = await readPlatform<OperatorSelfDocument>("me");
  const permissions = new Set(me?.permissions ?? []);
  if (!permissions.has("page.read")) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  const jar = await cookies();
  const tenantHint = (jar.get("subjex_org_tenant")?.value ?? "").trim();
  return (
    <section>
      <PageHeading title={phrases.searchTitle} hint={phrases.searchHint} />
      <SearchConsole phrases={phrases} initialTenantId={tenantHint} />
    </section>
  );
}
