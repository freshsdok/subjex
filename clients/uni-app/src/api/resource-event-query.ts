/**
 * Pure query helpers for explicit share/open events (no automatic page-view tracking).
 * 分享/打开事件的纯查询辅助（不会自动追踪普通浏览）。
 * <p>
 * Kept free of uni / platform imports so the open-vs-share rules can be unit-tested.
 * 不依赖 uni 或平台请求，便于单独测试何时才上报打开。
 */

export const RESOURCE_TYPE_QUESTIONNAIRE = "questionnaire";
export const RESOURCE_TYPE_FILE = "file";

/** Same token shape the platform ingest accepts — 与平台入库接受的标识形状相同. */
const RESOURCE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;

export type ResourceEventAttribution = {
  campaign?: string;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmContent?: string;
  utmTerm?: string;
  referrer?: string;
};

export type QueryMap = Record<string, unknown> | undefined | null;

export function firstQuery(query: QueryMap, ...keys: string[]): string {
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

export function isResourceIdToken(raw: string | undefined | null): boolean {
  return Boolean(raw && RESOURCE_ID_PATTERN.test(raw.trim()));
}

/**
 * True when this opening came from a share link or carried campaign / UTM fields.
 * 来自分享链接，或带有活动 / UTM 字段时为真。
 * Ordinary list visits (no shared flag, no campaign/UTM) are false.
 * 普通列表进入（无分享标记、无活动/UTM）为假。
 */
export function isSharedOpenQuery(query: QueryMap): boolean {
  const shared = firstQuery(query, "shared").toLowerCase();
  if (shared === "1" || shared === "true" || shared === "yes") {
    return true;
  }
  const attr = attributionFromQuery(query);
  return Boolean(attr.campaign || hasUtm(attr));
}

function hasUtm(attr: ResourceEventAttribution): boolean {
  return Boolean(attr.utmSource || attr.utmMedium || attr.utmCampaign || attr.utmContent || attr.utmTerm);
}

/** Read optional campaign / UTM / referrer fields the link already carried. */
export function attributionFromQuery(query: QueryMap): ResourceEventAttribution {
  return {
    campaign: firstQuery(query, "campaign") || undefined,
    utmSource: firstQuery(query, "utm_source", "utmSource") || undefined,
    utmMedium: firstQuery(query, "utm_medium", "utmMedium") || undefined,
    utmCampaign: firstQuery(query, "utm_campaign", "utmCampaign") || undefined,
    utmContent: firstQuery(query, "utm_content", "utmContent") || undefined,
    utmTerm: firstQuery(query, "utm_term", "utmTerm") || undefined,
    referrer: firstQuery(query, "referrer") || undefined,
  };
}

/**
 * Build a page query that marks the opening as a shared open and keeps attribution.
 * 构造页面查询：标记为来自分享的打开，并保留已有归因字段。
 * Does not copy a document referrer onto the link.
 * 不会把当前页的 document.referrer 写进链接。
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
  const tid = (tenantId || "").trim();
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

/** Prefer objectId, else objectKey, when they are ingest-safe tokens — 优先对象标识，否则对象键（须是可入库的令牌）. */
export function fileResourceId(item: { objectId?: string; objectKey?: string }): string {
  const id = (item.objectId || "").trim();
  if (isResourceIdToken(id)) return id;
  const key = (item.objectKey || "").trim();
  if (isResourceIdToken(key)) return key;
  return "";
}

/** File id from a shared/campaign entry query — 从分享/活动入口查询里取出文件标识. */
export function fileResourceIdFromQuery(query: QueryMap): string {
  const id = firstQuery(query, "objectId", "id");
  return isResourceIdToken(id) ? id : "";
}

/**
 * Resolve a captured referrer: query field first, then a runtime document referrer.
 * 解析已捕获的来源页：先看查询字段，再看运行时 document.referrer。
 */
export function resolvedReferrer(
  attribution: ResourceEventAttribution | undefined,
  documentReferrer?: string,
): string | undefined {
  const fromAttr = attribution?.referrer?.trim();
  if (fromAttr) return fromAttr;
  const fromDocument = (documentReferrer || "").trim();
  return fromDocument || undefined;
}
