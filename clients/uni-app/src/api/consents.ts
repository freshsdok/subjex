/**
 * End-user purpose-consent API (WP8 / consent UI).
 * 终端目的同意接口：列出当前授予、同意、按目的撤回、历史。
 *
 * Reuses {@code /api/v1/end-user/consents}. The kernel stores no legal text;
 * the caller supplies a purpose key and the text-version id the person saw.
 * 复用现有终端同意接口。内核不存放说明正文；由调用方提供用途键和当事人看到的文本版本。
 */

import { authedFetch } from "./platform";

/** Allowed purpose / version key (same as ConsentPort) — 与 ConsentPort 相同的用途/版本格式. */
export const CONSENT_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9._:-]{0,63}$/;

/** Current active grant — 当前仍然有效的同意. */
export type CurrentConsent = {
  tenantId?: string;
  subjectId?: string;
  purposeKey?: string;
  consentVersionId?: string;
  eventId?: string;
  grantedAt?: string;
};

/** Grant or withdraw event — 同意或撤回事件. */
export type ConsentHistoryEvent = {
  eventId?: string;
  tenantId?: string;
  subjectId?: string;
  purposeKey?: string;
  consentVersionId?: string;
  eventKind?: string;
  actorIdentityId?: string;
  occurredAt?: string;
};

/** Validate a purpose or version id before calling the API — 调用接口前校验用途或版本. */
export function requireConsentKey(value: string, label: string): string {
  const key = value.trim();
  if (!key) {
    throw new Error(`请填写${label}`);
  }
  if (!CONSENT_KEY_PATTERN.test(key)) {
    throw new Error(`${label}需要与说明上印刷的编号一致，只能用字母开头，并包含字母、数字或 . _ : -`);
  }
  return key;
}

export async function listCurrentConsents(): Promise<CurrentConsent[]> {
  const rows = await authedFetch<CurrentConsent[]>({
    path: "/api/v1/end-user/consents",
    tenantId: true,
  });
  return Array.isArray(rows) ? rows : [];
}

export async function listConsentHistory(purposeKey?: string): Promise<ConsentHistoryEvent[]> {
  const suffix =
    purposeKey && purposeKey.trim()
      ? `?purposeKey=${encodeURIComponent(purposeKey.trim())}`
      : "";
  const rows = await authedFetch<ConsentHistoryEvent[]>({
    path: `/api/v1/end-user/consents/history${suffix}`,
    tenantId: true,
  });
  return Array.isArray(rows) ? rows : [];
}

export async function grantConsent(purposeKey: string, consentVersionId: string): Promise<CurrentConsent> {
  const purpose = requireConsentKey(purposeKey, "用途编号");
  const version = requireConsentKey(consentVersionId, "文本版本");
  return authedFetch<CurrentConsent>({
    method: "POST",
    path: "/api/v1/end-user/consents",
    tenantId: true,
    body: { purposeKey: purpose, consentVersionId: version },
  });
}

export async function withdrawConsent(purposeKey: string): Promise<ConsentHistoryEvent> {
  const purpose = requireConsentKey(purposeKey, "用途编号");
  return authedFetch<ConsentHistoryEvent>({
    method: "POST",
    path: `/api/v1/end-user/consents/${encodeURIComponent(purpose)}/withdraw`,
    tenantId: true,
  });
}
