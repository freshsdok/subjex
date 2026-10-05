// Upstream platform address — 上游平台地址：Next.js 服务端转发 JSON 接口时要连的 subjex 平台。
export const platformApiBase: string =
  process.env.SUBJEX_API_BASE ?? "http://127.0.0.1:8080";

// Builds the Basic credential header value — 拼出 Basic 认证头的值（只在服务端使用，不下发浏览器）。
export function basicCredentialHeader(loginName: string, password: string): string {
  return "Basic " + Buffer.from(`${loginName}:${password}`, "utf8").toString("base64");
}
