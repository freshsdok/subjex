import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import type { components } from "@/api/schema";
import {
  PAGE_SERVICES,
  pageAllowed,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

type ServiceListDocument = components["schemas"]["ServiceListDocument"];

/** Status badge colors — 状态徽标：up 绿，其余醒目色；文字仍写出状态。 */
function statusBadgeClass(status: string | undefined): string {
  return status === "up"
    ? "border-green-600 text-green-700"
    : "border-danger text-danger";
}

/**
 * Services page — registered service instances.
 * Enter: Manifest {@code page.services} (registry.read).
 * 服务页：进入走 page.services。
 */
export default async function ServicesPage() {
  const { phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<ServiceListDocument>("services"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest =
    uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_SERVICES, permissions.has("registry.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="registry.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="registry.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const services = body.services ?? [];
  return (
    <section>
      <PageHeading title={phrases.servicesTitle} hint={phrases.servicesHint} />
      {services.length === 0 ? (
        <p className="text-sm text-muted">{phrases.emptyList}</p>
      ) : (
        <table className="w-full max-w-3xl border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.serviceNameColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.addressColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.statusColumn}</th>
            </tr>
          </thead>
          <tbody>
            {services.map((service) => (
              <tr key={`${service.serviceName}@${service.host}:${service.port}`} className="border-t border-border">
                <td className="px-3 py-2 font-medium">{service.serviceName}</td>
                <td className="px-3 py-2 font-mono text-xs">
                  {service.host}:{service.port}
                </td>
                <td className="px-3 py-2">
                  <span className={`rounded-full border px-2 py-0.5 text-xs ${statusBadgeClass(service.status)}`}>
                    {service.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
