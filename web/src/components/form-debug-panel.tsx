"use client";

import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import type { FormDebugState } from "@/lib/form-debug";

/**
 * Single debug/result panel for form and page-flow submits.
 * 表单与页面流程提交共用的调试/结果面板：校验、权限、落库、副作用摘要同处可见。
 */
export function FormDebugPanel({
  state,
  phrases,
  continueHref,
}: {
  state: FormDebugState;
  phrases: PhraseBook;
  /** Optional link after persist OK (page-flow redirect) — 落库成功后的可选继续链接（页面流程跳转） */
  continueHref?: string;
}) {
  if (state.kind === "idle") return null;

  if (state.kind === "validation") {
    return (
      <aside
        role="alert"
        className="mt-4 rounded-lg border border-danger/40 bg-surface p-4 text-sm"
        aria-label={phrases.formDebugTitle}
      >
        <p className="mb-2 font-semibold text-danger">{phrases.formDebugValidationTitle}</p>
        <p className="mb-2 text-xs text-muted">{phrases.formDebugValidationHint}</p>
        {state.fieldErrors.length === 0 ? (
          <p className="text-danger">{state.message || phrases.formDebugUnknownBody}</p>
        ) : (
          <ul className="space-y-1 font-mono text-xs">
            {state.fieldErrors.map((error) => (
              <li key={`${error.field}:${error.code}`}>
                <span className="font-semibold">{error.field}</span>
                <span className="text-muted"> · {error.code}</span>
                <span className="ml-2 text-danger">{error.message}</span>
              </li>
            ))}
          </ul>
        )}
      </aside>
    );
  }

  if (state.kind === "permission_denied") {
    return (
      <aside
        role="alert"
        className="mt-4 rounded-lg border border-danger/40 bg-surface p-4 text-sm"
        aria-label={phrases.formDebugTitle}
      >
        <p className="mb-2 font-semibold text-danger">{phrases.formDebugPermissionTitle}</p>
        <p className="text-sm">
          {fillPhrase(phrases.formDebugPermissionBody, { permission: state.permission })}
        </p>
      </aside>
    );
  }

  if (state.kind === "persist_ok") {
    const { persist } = state;
    return (
      <aside
        role="status"
        className="mt-4 rounded-lg border border-green-700/40 bg-surface p-4 text-sm"
        aria-label={phrases.formDebugTitle}
      >
        <p className="mb-2 font-semibold text-green-800">{phrases.formDebugPersistTitle}</p>
        <dl className="grid gap-1 font-mono text-xs">
          <div>
            <dt className="inline text-muted">{phrases.formDebugSubmissionId}: </dt>
            <dd className="inline">{persist.submissionId || "—"}</dd>
          </div>
          <div>
            <dt className="inline text-muted">{phrases.formDebugFormKey}: </dt>
            <dd className="inline">{persist.formKey || "—"}</dd>
          </div>
          <div>
            <dt className="inline text-muted">{phrases.formDebugDeclarationVersion}: </dt>
            <dd className="inline">{persist.declarationVersion || "—"}</dd>
          </div>
          <div>
            <dt className="inline text-muted">{phrases.formDebugSubmittedAt}: </dt>
            <dd className="inline">{persist.submittedAt || "—"}</dd>
          </div>
          <div>
            <dt className="inline text-muted">{phrases.formDebugResultSummary}: </dt>
            <dd className="inline">{persist.resultSummary || "—"}</dd>
          </div>
        </dl>
        {persist.effects.length > 0 ? (
          <div className="mt-3">
            <p className="mb-1 text-xs font-medium">{phrases.formDebugEffectsTitle}</p>
            <ul className="space-y-1 font-mono text-xs">
              {persist.effects.map((effect) => (
                <li key={effect.key}>
                  {effect.key}
                  <span className="ml-2 text-muted">{effect.outcome}</span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {continueHref ? (
          <p className="mt-3">
            <a href={continueHref} className="text-sm text-accent underline">
              {phrases.formDebugContinueAction}
            </a>
          </p>
        ) : null}
      </aside>
    );
  }

  return (
    <aside
      role="alert"
      className="mt-4 rounded-lg border border-danger/40 bg-surface p-4 text-sm"
      aria-label={phrases.formDebugTitle}
    >
      <p className="mb-1 font-semibold text-danger">{phrases.formDebugUnknownTitle}</p>
      <p className="text-sm">
        {state.message
          ? fillPhrase(phrases.formDebugUnknownWithMessage, {
              status: state.status,
              message: state.message,
            })
          : fillPhrase(phrases.formSubmitFailed, { status: state.status })}
      </p>
    </aside>
  );
}
