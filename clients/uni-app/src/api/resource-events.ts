/**
 * Thin helper to record a generic share or open of a resource (WP ops analytics).
 * 记下一次通用的内容分享或打开（运营分析）。
 * <p>
 * The client must call this explicitly. Ordinary page views are not posted automatically.
 * 必须由页面显式调用。普通浏览不会自动上报。
 * Works on H5 and the WeChat mini-program through {@code uni.request} / {@code platformFetch}.
 * 经 platformFetch 走 H5 与微信小程序同一路径。
 */

import { readBearer } from "./auth-token";
import { platformFetch } from "./platform";
import {
  RESOURCE_TYPE_FILE,
  RESOURCE_TYPE_QUESTIONNAIRE,
  attributionFromQuery,
  buildShareQueryString as buildShareQueryStringFromFields,
  fileResourceId,
  fileResourceIdFromQuery,
  isSharedOpenQuery,
  resolvedReferrer,
  type ResourceEventAttribution,
  type QueryMap,
} from "./resource-event-query";
import { readTenantId } from "./tenant";

export {
  RESOURCE_TYPE_FILE,
  RESOURCE_TYPE_QUESTIONNAIRE,
  attributionFromQuery,
  fileResourceId,
  fileResourceIdFromQuery,
  isSharedOpenQuery,
  resolvedReferrer,
};
export type { ResourceEventAttribution, QueryMap };

export type ResourceEventType = "share" | "open";

export type ResourceEventPayload = ResourceEventAttribution & {
  resourceType: string;
  resourceId: string;
  eventType: ResourceEventType;
  tenantId?: string;
};

/**
 * Build a fill/file-page query that marks the opening as a shared open and keeps attribution.
 * 构造填写页/文件页查询：标记为来自分享的打开，并保留已有归因字段。
 */
export function buildShareQueryString(
  base: Record<string, string | undefined>,
  attribution?: ResourceEventAttribution,
  tenantId?: string,
): string {
  return buildShareQueryStringFromFields(base, attribution, tenantId || readTenantId() || undefined);
}

/** H5 document.referrer when the runtime has a window; empty in the mini-program. */
export function runtimeDocumentReferrer(): string | undefined {
  try {
    if (typeof document !== "undefined" && document.referrer && document.referrer.trim()) {
      return document.referrer.trim();
    }
  } catch {
    // Mini-program or restricted runtime — 小程序或受限运行时。
  }
  return undefined;
}

/**
 * Post one share or open. Failures are swallowed by the caller so the page is not blocked.
 * 上报一次分享或打开。由调用方忽略失败，避免打断页面流程。
 */
export async function recordResourceEvent(payload: ResourceEventPayload): Promise<void> {
  const resourceType = payload.resourceType.trim();
  const resourceId = payload.resourceId.trim();
  if (!resourceType || !resourceId) {
    return;
  }
  const bearer = readBearer();
  const referrer = resolvedReferrer(payload, runtimeDocumentReferrer());
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
      referrer,
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
