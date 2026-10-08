// Upstream platform address — 上游平台地址：Next.js 服务端转发 JSON 接口时要连的 subjex 平台。
export const platformApiBase: string =
  process.env.SUBJEX_API_BASE ?? "http://127.0.0.1:8080";

/** @deprecated Console no longer stores Basic; kept for rare script helpers. */
export function basicCredentialHeader(loginName: string, password: string): string {
  return "Basic " + Buffer.from(`${loginName}:${password}`, "utf8").toString("base64");
}

export function bearerAuthorizationHeader(accessToken: string): string {
  return `Bearer ${accessToken}`;
}
