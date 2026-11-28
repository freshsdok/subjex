import { ForbiddenNotice, LoadFailedNotice, PageHeading } from "@/components/page-state";
import { currentLanguage } from "@/i18n/server-language";
import { PAGE_TASKS, pageAllowed, type UiPermissionManifestDocument } from "@/lib/ui-permissions";
import { readPlatform, type OperatorSelfDocument } from "@/server/platform-reader";

/**
 * Operator tasks — read-only list of existing platform_task rows (GET /api/v1/tasks).
 * Enter: Manifest {@code page.tasks} ({@code admin.read}). No retry, confirm, or scheduler.
 * <p>
 * 操作员任务：只读列出已有任务行。进入走 page.tasks（admin.read）。不提供重试、确认或排程。
 */
type TasksDocument = {
  tasks?: Array<{
    taskId?: string;
    tenantId?: string;
    taskKind?: string;
    taskState?: string;
    stepName?: string;
    attemptCount?: number;
    maxAttempt?: number;
    modelId?: string | null;
    humanConfirmation?: string;
    failureReason?: string | null;
    createdAt?: string;
  }>;
};

export default async function TasksPage() {
  const { language, phrases } = await currentLanguage();
  const [{ status, body }, { body: me }, uiPermissionsRead] = await Promise.all([
    readPlatform<TasksDocument>("tasks"),
    readPlatform<OperatorSelfDocument>("me"),
    readPlatform<UiPermissionManifestDocument>("ui-permissions?client=console"),
  ]);
  const permissions = new Set(me?.permissions ?? []);
  const manifest = uiPermissionsRead.status === 200 ? uiPermissionsRead.body : undefined;
  if (!pageAllowed(manifest, PAGE_TASKS, permissions.has("admin.read"))) {
    return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  }
  if (status === 403) return <ForbiddenNotice phrases={phrases} permission="admin.read" />;
  if (!body) return <LoadFailedNotice phrases={phrases} status={status} />;
  const tasks = [...(body.tasks ?? [])].sort((left, right) =>
    (right.createdAt ?? "").localeCompare(left.createdAt ?? ""),
  );
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });
  return (
    <section>
      <PageHeading title={phrases.tasksTitle} hint={phrases.tasksHint} />
      {tasks.length === 0 ? (
        <p className="text-sm text-muted">{phrases.tasksEmpty}</p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.taskCreatedColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskIdColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskTenantColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskKindColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskStateColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskStepColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskAttemptsColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskConfirmationColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskModelColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.taskFailureColumn}</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => (
              <tr key={task.taskId} className="border-t border-border">
                <td className="whitespace-nowrap px-3 py-2">
                  {task.createdAt ? timeFormat.format(new Date(task.createdAt)) : ""}
                </td>
                <td className="px-3 py-2 font-mono text-xs">{task.taskId}</td>
                <td className="px-3 py-2 font-mono text-xs">{task.tenantId}</td>
                <td className="px-3 py-2">{task.taskKind}</td>
                <td className="px-3 py-2">{task.taskState}</td>
                <td className="px-3 py-2">{task.stepName}</td>
                <td className="whitespace-nowrap px-3 py-2">
                  {task.attemptCount ?? 0}/{task.maxAttempt ?? 0}
                </td>
                <td className="px-3 py-2">{task.humanConfirmation}</td>
                <td className="px-3 py-2 font-mono text-xs">{task.modelId ?? ""}</td>
                <td className="px-3 py-2">{task.failureReason ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
