/**
 * Thin helper to record a generic share or open of a resource (WP ops analytics first cut).
 * 记下一次通用的内容分享或打开（运营分析首切口）。
 * <p>
 * The client must call this explicitly. Ordinary page views are not posted automatically.
 * 必须由页面显式调用。普通浏览不会自动上报。
 * Works on H5 and the WeChat mini-program through {@code uni.request} / {@code platformFetch}.
 * 经 platformFetch 走 H5 与微信小程序同一路径。
 */

import { readBearer } from "./auth-token";
import { platformFetch } from "./platform";
import { readTenantId } from "./tenant";

export const RESOURCE_TYPE_QUESTIONNAIRE = "questionnaire";

export type ResourceEventType = "share" | "open";

export type ResourceEventAttribution = {
  campaign?: string;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmContent?: string;
  utmTerm?: string;
  referrer?: string;
};

export type ResourceEventPayload = ResourceEventAttribution & {
  resourceType: string;
  resourceId: string;
  eventType: ResourceEventType;
  tenantId?: string;
};

type QueryMap = Record<string, unknown> | undefined | null;

function firstQuery(query: QueryMap, ...keys: string[]): string {
  if (!query) return "";
  for (const key of keys) {
    const raw = query[key];
    const value = Array.isArray(raw) ? raw[0] : raw;
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

/** True when this opening came from a share link or carried campaign / UTM fields. */
export function isSharedOpenQuery(query: QueryMap): boolean {
  const shared = firstQuery(query, "shared").toLowerCase();
  if (shared === "1" || shared === "true" || shared === "yes") {
    return true;
  }
  return Boolean(attributionFromQuery(query).campaign || hasUtm(attributionFromQuery(query)));
}

function hasUtm(attr: ResourceEventAttribution): boolean {
  return Boolean(attr.utmSource || attr.utmMedium || attr.utmCampaign || attr.utmContent || attr.utmTerm);
}

/** Read optional campaign / UTM fields the share link already carried. */
export function attributionFromQuery(query: QueryMap): ResourceEventAttribution {
  return {
    campaign: firstQuery(query, "campaign") || undefined,
    utmSource: firstQuery(query, "utm_source", "utmSource") || undefined,
    utmMedium: firstQuery(query, "utm_medium", "utmMedium") || undefined,
    utmCampaign: firstQuery(query, "utm_campaign", "utmCampaign") || undefined,
    utmContent: firstQuery(query, "utm_content", "utmContent") || undefined,
    utmTerm: firstQuery(query, "utm_term", "utmTerm") || undefined,
  };
}

/**
 * Build a fill-page query that marks the opening as a shared open and keeps attribution.
 * 构造填写页查询：标记为来自分享的打开，并保留已有归因字段。
 */
export function buildShareQueryString(
  base: Record<string, string | undefined>,
  attribution?: ResourceEventAttribution,
  tenantId?: string,
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(base)) {
    if (value && value.trim()) {
      params.set(key, value.trim());
    }
  }
  params.set("shared", "1");
  const tid = (tenantId || readTenantId() || "").trim();
  if (tid) {
    params.set("tenantId", tid);
  }
  const attr = attribution ?? {};
  if (attr.campaign) params.set("campaign", attr.campaign);
  if (attr.utmSource) params.set("utm_source", attr.utmSource);
  if (attr.utmMedium) params.set("utm_medium", attr.utmMedium);
  if (attr.utmCampaign) params.set("utm_campaign", attr.utmCampaign);
  if (attr.utmContent) params.set("utm_content", attr.utmContent);
  if (attr.utmTerm) params.set("utm_term", attr.utmTerm);
  return params.toString();
}

/**
 * Post one share or open. Failures are swallowed by the caller so filling is not blocked.
 * 上报一次分享或打开。由调用方忽略失败，避免打断填写。
 */
export async function recordResourceEvent(payload: ResourceEventPayload): Promise<void> {
  const resourceType = payload.resourceType.trim();
  const resourceId = payload.resourceId.trim();
  if (!resourceType || !resourceId) {
    return;
  }
  const bearer = readBearer();
  await platformFetch({
    method: "POST",
    path: "/api/v1/resource-events",
    bearer: bearer || undefined,
    tenantId: payload.tenantId?.trim() || true,
    skipAuthRetry: true,
    body: {
      resourceType,
      resourceId,
      eventType: payload.eventType,
      campaign: payload.campaign,
      utmSource: payload.utmSource,
      utmMedium: payload.utmMedium,
      utmCampaign: payload.utmCampaign,
      utmContent: payload.utmContent,
      utmTerm: payload.utmTerm,
      referrer: payload.referrer,
    },
  });
}

/** Best-effort record; never throws into the page flow — 尽力上报，不把错误抛进页面流程。 */
export function tryRecordResourceEvent(payload: ResourceEventPayload): void {
  void recordResourceEvent(payload).catch(() => {
    // Ignore: recording must not block share or fill.
    // 忽略：上报失败不得打断分享或填写。
  });
}
