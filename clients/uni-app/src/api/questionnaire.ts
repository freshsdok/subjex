/**
 * Questionnaire fill API (Q7 / WP7-3) — public ANON collect + end-user AUTH list/submit.
 * 问卷填写：公开匿名采集 + 终端登录后的列表与提交。
 */

import { authedFetch, platformFetch } from "./platform";
import { requireBusinessTenantId } from "./tenant";

const CLIENT_KEY_STORAGE = "subjex.questionnaire.clientKey";

export type QuestionnaireItem = {
  itemId?: string;
  sortOrder?: number;
  questionType?: string;
  prompt?: string;
  required?: boolean;
  optionsJson?: string;
};

export type QuestionnaireDefinition = {
  questionnaireId?: string;
  tenantId?: string;
  title?: string;
  status?: string;
  revision?: number;
  collectMode?: string;
  items?: QuestionnaireItem[];
};

export type QuestionnaireAnswerPayload = {
  itemId: string;
  valueJson: string;
};

export type QuestionnaireResponseDoc = {
  responseId?: string;
  questionnaireId?: string;
  revision?: number;
  respondentRealm?: string;
  countsForStats?: boolean;
};

/** Stable anon client key for rate-limit buckets — 匿名限流用的稳定客户端键。 */
export function ensureClientKey(): string {
  try {
    const existing = uni.getStorageSync(CLIENT_KEY_STORAGE);
    if (typeof existing === "string" && existing.trim()) {
      return existing.trim();
    }
  } catch {
    /* ignore */
  }
  const generated =
    "ck-" +
    Date.now().toString(36) +
    "-" +
    Math.random().toString(36).slice(2, 10);
  try {
    uni.setStorageSync(CLIENT_KEY_STORAGE, generated);
  } catch {
    /* ignore */
  }
  return generated;
}

/** Encode a UI value as valueJson for submit — 把界面值编码为提交用 valueJson。 */
export function encodeAnswerValue(raw: unknown): string {
  return JSON.stringify(raw);
}

/** Parse options_json into string choices — 解析 options_json 为选项文案。 */
export function parseOptions(optionsJson: string | undefined | null): string[] {
  if (!optionsJson || !optionsJson.trim()) return [];
  try {
    const parsed = JSON.parse(optionsJson) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((x): x is string => typeof x === "string");
  } catch {
    return [];
  }
}

/**
 * Public GET definition by collect token (no auth).
 * 凭采集令牌公开取定义（无需登录）。
 */
export async function publicGetByToken(tokenId: string): Promise<QuestionnaireDefinition> {
  const tid = tokenId.trim();
  if (!tid) throw new Error("tokenId required");
  return platformFetch<QuestionnaireDefinition>({
    path: `/api/v1/questionnaires/public/${encodeURIComponent(tid)}`,
  });
}

/**
 * Public ANON submit by collect token.
 * 凭采集令牌匿名提交。
 */
export async function publicSubmit(
  tokenId: string,
  answers: QuestionnaireAnswerPayload[],
  clientKey?: string
): Promise<QuestionnaireResponseDoc> {
  const tid = tokenId.trim();
  if (!tid) throw new Error("tokenId required");
  const key = (clientKey && clientKey.trim()) || ensureClientKey();
  return platformFetch<QuestionnaireResponseDoc>({
    method: "POST",
    path: `/api/v1/questionnaires/public/${encodeURIComponent(tid)}/responses`,
    headers: { "X-Client-Key": key },
    body: { clientKey: key, answers },
  });
}

/**
 * Published AUTH_END_USER questionnaires for the signed-in end user (current tenant).
 * 已登录终端用户、当前租户下已发布的认证问卷列表。
 */
export async function listAuthenticated(
  tenantId?: string,
  limit = 50
): Promise<QuestionnaireDefinition[]> {
  const tid = requireBusinessTenantId(tenantId);
  const q = new URLSearchParams();
  if (limit) q.set("limit", String(limit));
  const suffix = q.toString() ? `?${q.toString()}` : "";
  const doc = await authedFetch<{ questionnaires?: QuestionnaireDefinition[] }>({
    path: `/api/v1/end-user/questionnaires${suffix}`,
    tenantId: tid,
  });
  return doc.questionnaires ?? [];
}

/** Fetch one visible questionnaire — 读取一份对终端可见的问卷. */
export async function getQuestionnaire(
  questionnaireId: string,
  tenantId?: string
): Promise<QuestionnaireDefinition> {
  const id = questionnaireId.trim();
  if (!id) throw new Error("请选择一份问卷");
  return authedFetch<QuestionnaireDefinition>({
    path: `/api/v1/end-user/questionnaires/${encodeURIComponent(id)}`,
    tenantId: requireBusinessTenantId(tenantId),
  });
}

/** Submit as the signed-in end user (409 if already submitted). / 以登录用户提交；已提交过则 409. */
export async function submitQuestionnaire(
  questionnaireId: string,
  answers: QuestionnaireAnswerPayload[],
  tenantId?: string
): Promise<QuestionnaireResponseDoc> {
  const id = questionnaireId.trim();
  if (!id) throw new Error("请选择一份问卷");
  return authedFetch<QuestionnaireResponseDoc>({
    method: "POST",
    path: `/api/v1/end-user/questionnaires/${encodeURIComponent(id)}/responses`,
    tenantId: requireBusinessTenantId(tenantId),
    body: { answers },
  });
}
