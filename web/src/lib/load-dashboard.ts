/**
 * Format helpers for the operator load dashboard. They only format already-fetched numbers.
 * 负载页数字格式化；只整理已经取回的数字，不编造指标。
 */

export function formatBytes(value: number | null | undefined, language: string): string | null {
  if (value == null || !Number.isFinite(value) || value < 0) return null;
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let amount = value;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  const digits = unit === 0 ? 0 : amount >= 10 ? 1 : 2;
  const formatted = new Intl.NumberFormat(language === "zh" ? "zh-CN" : "en", {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(amount);
  return `${formatted} ${units[unit]}`;
}

export function formatPercent(value: number | null | undefined, language: string): string | null {
  if (value == null || !Number.isFinite(value)) return null;
  const formatted = new Intl.NumberFormat(language === "zh" ? "zh-CN" : "en", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(value);
  return formatted;
}

export function formatDurationSeconds(value: number | null | undefined, language: string): string | null {
  if (value == null || !Number.isFinite(value) || value < 0) return null;
  const total = Math.floor(value);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (language === "zh") {
    if (days > 0) return `${days} 天 ${hours} 小时`;
    if (hours > 0) return `${hours} 小时 ${minutes} 分钟`;
    if (minutes > 0) return `${minutes} 分钟 ${seconds} 秒`;
    return `${seconds} 秒`;
  }
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function formatCount(value: number | null | undefined, language: string): string | null {
  if (value == null || !Number.isFinite(value)) return null;
  return new Intl.NumberFormat(language === "zh" ? "zh-CN" : "en", { maximumFractionDigits: 0 }).format(
    Math.round(value),
  );
}

/** Map known probe names to phrase keys; unknown values stay raw. — 已知探针名走文案键，未知值原样显示。 */
export function healthPhraseKey(state: string | undefined): "loadLivenessUp" | "loadReadinessUp" | "loadHealthDown" | null {
  const value = (state ?? "").toUpperCase();
  if (value === "CORRECT") return "loadLivenessUp";
  if (value === "ACCEPTING_TRAFFIC") return "loadReadinessUp";
  if (value === "BROKEN" || value === "REFUSING_TRAFFIC") return "loadHealthDown";
  return null;
}
