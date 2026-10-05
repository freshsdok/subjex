// Form submit debug helpers — 表单提交调试辅助：把 API 成功/失败体收成一块面板状态。

export type FormFieldError = {
  field: string;
  code: string;
  message: string;
};

export type EffectOutcome = {
  key: string;
  outcome: string;
};

export type FormDebugPersist = {
  submissionId: string;
  formKey: string;
  declarationVersion: number;
  resultSummary: string;
  submittedAt: string;
  effects: EffectOutcome[];
};

export type FormDebugState =
  | { kind: "idle" }
  | { kind: "validation"; fieldErrors: FormFieldError[]; message: string }
  | { kind: "permission_denied"; permission: string; message: string }
  | { kind: "persist_ok"; persist: FormDebugPersist }
  | { kind: "unknown"; status: number; message: string };

type ProblemBody = {
  kind?: string;
  fieldErrors?: Array<{ field?: string; code?: string; message?: string }>;
  permission?: string;
  message?: string;
  reason?: string;
};

type SuccessBody = {
  submissionId?: string;
  formKey?: string;
  declarationVersion?: number;
  resultSummary?: string;
  submittedAt?: string;
  effects?: Array<{ key?: string; outcome?: string }>;
};

/**
 * Map HTTP status + JSON body from form/page submit into one debug panel model.
 * 把表单/页面提交的 HTTP 状态与 JSON 收成调试面板模型。
 */
export function parseSubmitReply(status: number, body: unknown): FormDebugState {
  if (status >= 200 && status < 300) {
    const ok = (body ?? {}) as SuccessBody;
    return {
      kind: "persist_ok",
      persist: {
        submissionId: ok.submissionId ?? "",
        formKey: ok.formKey ?? "",
        declarationVersion: typeof ok.declarationVersion === "number" ? ok.declarationVersion : 0,
        resultSummary: ok.resultSummary ?? "",
        submittedAt: ok.submittedAt ?? "",
        effects: Array.isArray(ok.effects)
          ? ok.effects
              .filter((row): row is { key: string; outcome: string } => Boolean(row?.key && row?.outcome))
              .map((row) => ({ key: row.key, outcome: row.outcome }))
          : [],
      },
    };
  }

  const problem = (body ?? {}) as ProblemBody;
  if (status === 403 && problem.kind === "permission_denied") {
    return {
      kind: "permission_denied",
      permission: problem.permission ?? "permission",
      message: problem.message ?? "",
    };
  }
  if (status === 400 && problem.kind === "validation") {
    const fieldErrors = Array.isArray(problem.fieldErrors)
      ? problem.fieldErrors
          .filter((row): row is { field: string; code: string; message: string } =>
            Boolean(row?.field && row?.code && row?.message),
          )
          .map((row) => ({ field: row.field, code: row.code, message: row.message }))
      : [];
    return {
      kind: "validation",
      fieldErrors,
      message: problem.message ?? "",
    };
  }
  const fallback =
    typeof problem.message === "string" && problem.message
      ? problem.message
      : typeof problem.reason === "string" && problem.reason
        ? problem.reason
        : "";
  return { kind: "unknown", status, message: fallback };
}
