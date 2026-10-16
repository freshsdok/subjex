/**
 * Client helpers for feature-flag API documents (config namespace flags).
 * Server authority is FeatureFlagService.isEnabled; this only interprets responses.
 * 客户端开关文档辅助；权威在服务端 FeatureFlagService.isEnabled。
 */

export type FlagDocument = {
  key?: string;
  flagKey?: string;
  value?: string;
  enabled?: boolean | null;
};

/** Interpret raw flag value the same way as the server — 与服务端相同的取值解释。 */
export function parseFlagEnabled(raw: string | null | undefined): boolean | null {
  if (raw == null || raw.trim() === "") return null;
  switch (raw.trim().toLowerCase()) {
    case "true":
    case "1":
    case "yes":
    case "on":
      return true;
    case "false":
    case "0":
    case "no":
    case "off":
      return false;
    default:
      return null;
  }
}

/** Prefer API {@code enabled}, else parse {@code value} — 优先 API enabled，否则解析 value。 */
export function flagIsEnabled(flag: FlagDocument | undefined, defaultValue: boolean): boolean {
  if (!flag) return defaultValue;
  if (typeof flag.enabled === "boolean") return flag.enabled;
  const parsed = parseFlagEnabled(flag.value);
  return parsed == null ? defaultValue : parsed;
}
