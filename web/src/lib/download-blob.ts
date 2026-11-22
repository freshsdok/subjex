/**
 * Trigger a same-origin file download from a fetched blob, and parse a filename header.
 * 用已取到的二进制触发同域下载，并解析文件名响应头。
 */

/**
 * Prefer RFC 5987 {@code filename*}, then a quoted {@code filename}.
 * 优先 RFC 5987 的 filename*，否则用带引号的 filename。
 */
export function filenameFromContentDisposition(header: string | null | undefined, fallback: string): string {
  const raw = (header ?? "").trim();
  if (!raw) return fallback;
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(raw);
  if (star?.[1]) {
    const token = star[1].trim().replace(/^"+|"+$/g, "");
    try {
      return decodeURIComponent(token) || fallback;
    } catch {
      return token || fallback;
    }
  }
  const quoted = /filename="([^"]+)"/i.exec(raw);
  if (quoted?.[1]) return quoted[1];
  const plain = /filename=([^;]+)/i.exec(raw);
  if (plain?.[1]) return plain[1].trim().replace(/^"+|"+$/g, "") || fallback;
  return fallback;
}

/** Create a temporary object URL and click a hidden <a download> — 建临时地址并点击隐藏下载链接。 */
export function downloadBlob(data: Blob, filename: string): void {
  const name = filename.trim() || "download";
  const url = URL.createObjectURL(data);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
