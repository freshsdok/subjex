/**
 * Public published content pages in the current tenant. Drafts are not returned.
 * 当前租户已发布的公开内容页。草稿不会返回。
 */

import { apiBase, discoverTenantFromApiHost, platformFetch } from "./platform";
import { readTenantId } from "./tenant";

export type PublicContentBlock = {
  blockId?: string;
  type?: string;
  text?: string | null;
  format?: string | null;
  objectId?: string | null;
  href?: string | null;
  alt?: string | null;
  mediaUrl?: string | null;
};

export type PublicContentPage = {
  slug?: string;
  title?: string;
  status?: string;
  publishedAt?: string | null;
  blocks?: PublicContentBlock[];
};

export type PublicContentSummary = {
  slug?: string;
  title?: string;
  publishedAt?: string | null;
};

const CONTENT_PATH = "/api/v1/public/content-pages";

async function ensureTenant(): Promise<void> {
  if (readTenantId()) return;
  await discoverTenantFromApiHost();
}

/** Published pages in the current tenant — 当前租户已发布的页面列表. */
export async function listPublishedContentPages(): Promise<PublicContentSummary[]> {
  await ensureTenant();
  const body = await platformFetch<PublicContentSummary[]>({
    path: CONTENT_PATH,
    tenantId: true,
    skipAuthRetry: true,
  });
  return Array.isArray(body) ? body : [];
}

/** One published page by slug; drafts and missing pages are not found — 按别名取已发布页. */
export async function getPublishedContentPage(slug: string): Promise<PublicContentPage> {
  await ensureTenant();
  const id = slug.trim();
  return platformFetch<PublicContentPage>({
    path: `${CONTENT_PATH}/${encodeURIComponent(id)}`,
    tenantId: true,
    skipAuthRetry: true,
  });
}

/** Absolute URL for a published image block — 已发布图片块的绝对地址. */
export function publishedMediaSrc(block: PublicContentBlock): string {
  const path = (block.mediaUrl || "").trim();
  if (!path) return "";
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  const base = apiBase().replace(/\/$/, "");
  const suffix = path.startsWith("/") ? path : `/${path}`;
  const tenant = readTenantId();
  if (tenant && !/[?&]tenantId=/.test(suffix)) {
    return `${base}${suffix}${suffix.includes("?") ? "&" : "?"}tenantId=${encodeURIComponent(tenant)}`;
  }
  return `${base}${suffix}`;
}
