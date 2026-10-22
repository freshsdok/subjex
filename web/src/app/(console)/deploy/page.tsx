import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  PAGE_DEPLOY,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type DeployDocument = components["schemas"]["DeployDocument"];

/**
 * Deploy page — applied vs workload inventory (do not confuse manifest with live cluster).
 * Enter: Manifest {@code page.deploy} (page.read).
 * 部署页：进入走 page.deploy；先说清是否已应用，再列工作负载。
 */
export default async function DeployPage() {
  const { phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<DeployDocument>("deploy"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_DEPLOY, permissions.has("page.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="page.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const workloads = body.workloads ?? [];
  return (
    <section>
      <PageHeading title={phrases.deployTitle} hint={phrases.deployHint} />
      <p role="status" className="mb-4 max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm">
        {body.applied ? phrases.deployApplied : phrases.deployNotApplied}
      </p>
      {workloads.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.workloadColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.imageColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.probesColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.memoryColumn}</th>
            </tr>
          </thead>
          <tbody>
            {workloads.map((workload) => (
              <tr key={workload.name} className="border-t border-border">
                <td className="px-3 py-2 font-medium">{workload.name}</td>
                <td className="px-3 py-2 font-mono text-xs">{workload.image}</td>
                <td className="px-3 py-2 font-mono text-xs">
                  {workload.livenessPath}
                  <br />
                  {workload.readinessPath}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{workload.memoryLimit}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

