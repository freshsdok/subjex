import { ForbiddenNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import {
  PAGE_SEARCH,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";
import { cookies } from "next/headers";
import { SearchConsole } from "./search-console";

/**
 * Search page — read-only entity_record search.
 * Enter: Manifest {@code page.search} (page.read).
 * 搜索页：进入走 page.search。
 */
export default async function SearchPage() {
  const { phrases } = await currentLanguage();
  const [{ body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_SEARCH, permissions.has("page.read"))) {
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
