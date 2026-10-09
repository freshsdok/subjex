import type { LanguageCode } from "@/i18n/phrases";

// Capabilities console helpers — 能力控制台辅助：按语言取标题/摘要、试跑路径。

export type CapabilityRow = {
  id: string;
  kind: string;
  titleEn: string;
  titleZh: string;
  summaryEn: string;
  summaryZh: string;
};

/** Title for the active console language — 按当前语言取标题。 */
export function capabilityTitle(row: CapabilityRow, language: LanguageCode): string {
  return language === "en" ? row.titleEn : row.titleZh;
}

/** Short summary for the active console language — 按当前语言取摘要。 */
export function capabilitySummary(row: CapabilityRow, language: LanguageCode): string {
  return language === "en" ? row.summaryEn : row.summaryZh;
}

/** Platform-proxy path for try-run — 试跑代理路径。 */
export function capabilityRunPath(capabilityId: string): string {
  return `/api/platform/capabilities/${encodeURIComponent(capabilityId)}/run`;
}

/** Known catalog kinds — 已知目录种类。 */
export function isKnownCapabilityKind(kind: string): kind is "ALGORITHM" | "AI" {
  return kind === "ALGORITHM" || kind === "AI";
}
