/**
 * End-user objects (WP7-3) — 终端文件。
 * List/upload {@code /api/v1/end-user/objects}; download via signed links.
 * Files larger than the advised chunk size use the CHUNK-1 session API
 * (init / chunks / complete). Small files still use one multipart POST.
 * 列表与上传走终端对象接口；打开文件走已有签名下载。
 * 大于建议分片大小的文件走分片会话；小文件仍一次 multipart 上传。
 */

import { apiBase, authedFetch, PlatformHttpError } from "./platform";
import { readBearer } from "./auth-token";
import { TENANT_HEADER, readTenantId, requireBusinessTenantId } from "./tenant";

/** Advised chunk size, same as ChunkedBlobUploadStore.DEFAULT_CHUNK_SIZE — 建议分片大小，与后端默认一致. */
export const DEFAULT_CHUNK_SIZE = 256 * 1024;

/** Advertised single-file cap (50 MiB) — 对外单文件上限. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

export type EndUserObject = {
  objectId?: string;
  objectKey: string;
  ownerSubjectId?: string;
  createdAt?: string;
  mediaType?: string | null;
  sizeBytes?: number | null;
};

export type IssuedDownloadLink = {
  url: string;
  expiresAt?: string;
  ttl?: string;
  backend?: string;
};

type UploadInit = {
  uploadId: string;
  totalBytes: number;
  chunkSize: number;
  chunkCount: number;
  maxBytes: number;
};

export async function listObjects(limit = 50, offset = 0): Promise<EndUserObject[]> {
  const q = new URLSearchParams();
  q.set("limit", String(limit));
  q.set("offset", String(offset));
  const doc = await authedFetch<{ objects?: EndUserObject[] }>({
    path: `/api/v1/end-user/objects?${q.toString()}`,
    tenantId: true,
  });
  return doc.objects ?? [];
}

export type PickedFile = { filePath: string; name?: string };

/**
 * Pick one file with uni APIs (message-file / chooseFile / chooseImage).
 * 用 uni API 选择一个文件（尽量不依赖条件编译）。
 */
export function pickFile(): Promise<PickedFile> {
  return new Promise((resolve, reject) => {
    const chooseMessageFile = (uni as unknown as { chooseMessageFile?: Function }).chooseMessageFile;
    if (typeof chooseMessageFile === "function") {
      chooseMessageFile({
        count: 1,
        type: "file",
        success: (res: { tempFiles?: Array<{ path?: string; name?: string }> }) => {
          const f = res.tempFiles && res.tempFiles[0];
          if (!f || !f.path) {
            reject(new Error("没有选择文件"));
            return;
          }
          resolve({ filePath: f.path, name: f.name });
        },
        fail: (err: { errMsg?: string }) => reject(new Error(err.errMsg || "没有选择文件")),
      });
      return;
    }
    const chooseFile = (uni as unknown as { chooseFile?: Function }).chooseFile;
    if (typeof chooseFile === "function") {
      chooseFile({
        count: 1,
        success: (res: { tempFiles?: Array<{ path?: string; tempFilePath?: string; name?: string }> }) => {
          const f = res.tempFiles && res.tempFiles[0];
          const path = f && (f.path || f.tempFilePath);
          if (!path) {
            reject(new Error("没有选择文件"));
            return;
          }
          resolve({ filePath: path, name: f?.name });
        },
        fail: (err: { errMsg?: string }) => reject(new Error(err.errMsg || "没有选择文件")),
      });
      return;
    }
    uni.chooseImage({
      count: 1,
      success: (res) => {
        const path = res.tempFilePaths && res.tempFilePaths[0];
        if (!path) {
          reject(new Error("没有选择文件"));
          return;
        }
        resolve({ filePath: path });
      },
      fail: (err) => reject(new Error(err.errMsg || "没有选择文件")),
    });
  });
}

/** Guess a MIME type from the file name — 按文件名猜测媒体类型. */
export function guessContentType(nameOrPath?: string): string | undefined {
  const raw = (nameOrPath || "").split("?")[0].trim().toLowerCase();
  const dot = raw.lastIndexOf(".");
  if (dot < 0) return undefined;
  const ext = raw.slice(dot + 1);
  if (ext === "pdf") return "application/pdf";
  if (ext === "png") return "image/png";
  if (ext === "jpg" || ext === "jpeg") return "image/jpeg";
  if (ext === "webp") return "image/webp";
  if (ext === "txt") return "text/plain";
  if (ext === "docx") {
    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  }
  if (ext === "xlsx") {
    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  }
  if (ext === "pptx") {
    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  }
  return undefined;
}

function fileSize(filePath: string): Promise<number | null> {
  return new Promise((resolve) => {
    uni.getFileInfo({
      filePath,
      success: (res) => resolve(typeof res.size === "number" ? res.size : null),
      fail: () => resolve(null),
    });
  });
}

function getFileSystemManager(): { readFile: Function } | null {
  const uniFs = (uni as unknown as { getFileSystemManager?: () => { readFile: Function } })
    .getFileSystemManager;
  if (typeof uniFs === "function") {
    return uniFs.call(uni);
  }
  const wxObj = (globalThis as unknown as { wx?: { getFileSystemManager?: () => { readFile: Function } } }).wx;
  if (wxObj && typeof wxObj.getFileSystemManager === "function") {
    return wxObj.getFileSystemManager();
  }
  return null;
}

/** Read one slice as ArrayBuffer (H5 blob URL or mini-program fs). / 读取一分片。 */
async function readFileSlice(filePath: string, start: number, length: number): Promise<ArrayBuffer> {
  if (filePath.startsWith("blob:") || filePath.startsWith("http://") || filePath.startsWith("https://")) {
    const res = await fetch(filePath);
    const blob = await res.blob();
    return await blob.slice(start, start + length).arrayBuffer();
  }
  const fs = getFileSystemManager();
  if (fs) {
    return await new Promise<ArrayBuffer>((resolve, reject) => {
      fs.readFile({
        filePath,
        position: start,
        length,
        success: (res: { data?: ArrayBuffer | string }) => {
          if (res.data instanceof ArrayBuffer) {
            resolve(res.data);
            return;
          }
          reject(new Error("无法读取文件分片"));
        },
        fail: (err: { errMsg?: string }) => reject(new Error(err.errMsg || "无法读取文件分片")),
      });
    });
  }
  // H5 local / tmp paths: try fetch as a last resort — 其他路径再尝试 fetch。
  const res = await fetch(filePath);
  const blob = await res.blob();
  return await blob.slice(start, start + length).arrayBuffer();
}

async function abortUpload(uploadId: string): Promise<void> {
  try {
    await authedFetch({
      method: "DELETE",
      path: `/api/v1/end-user/objects/uploads/${encodeURIComponent(uploadId)}`,
      tenantId: true,
    });
  } catch {
    // Best-effort cleanup — 尽量清理，失败不影响主错误。
  }
}

async function uploadChunked(file: PickedFile, totalBytes: number, contentType: string): Promise<EndUserObject> {
  const session = await authedFetch<UploadInit>({
    method: "POST",
    path: "/api/v1/end-user/objects/uploads",
    tenantId: true,
    body: { contentType, totalBytes },
  });
  const uploadId = session.uploadId;
  const chunkSize = session.chunkSize > 0 ? session.chunkSize : DEFAULT_CHUNK_SIZE;
  const chunkCount = session.chunkCount;
  try {
    for (let i = 0; i < chunkCount; i++) {
      const start = i * chunkSize;
      const length = Math.min(chunkSize, totalBytes - start);
      const slice = await readFileSlice(file.filePath, start, length);
      await authedFetch({
        method: "PUT",
        path: `/api/v1/end-user/objects/uploads/${encodeURIComponent(uploadId)}/chunks/${i}`,
        tenantId: true,
        rawBody: slice,
        headers: { "Content-Type": "application/octet-stream" },
      });
    }
    return await authedFetch<EndUserObject>({
      method: "POST",
      path: `/api/v1/end-user/objects/uploads/${encodeURIComponent(uploadId)}/complete`,
      tenantId: true,
    });
  } catch (err) {
    await abortUpload(uploadId);
    throw err;
  }
}

/** One-shot multipart for small files — 小文件一次分件上传，字段名必须是 file. */
async function uploadMultipart(file: PickedFile): Promise<EndUserObject> {
  const bearer = readBearer();
  if (!bearer) throw new Error("请先登录");
  const tenant = requireBusinessTenantId(readTenantId());
  const url = `${apiBase().replace(/\/$/, "")}/api/v1/end-user/objects`;
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url,
      filePath: file.filePath,
      name: "file",
      header: {
        Authorization: `Bearer ${bearer}`,
        [TENANT_HEADER]: tenant,
        Accept: "application/json",
      },
      success: (res) => {
        const text = typeof res.data === "string" ? res.data : JSON.stringify(res.data ?? "");
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new PlatformHttpError("POST", "/api/v1/end-user/objects", res.statusCode, "", text));
          return;
        }
        try {
          resolve(JSON.parse(text) as EndUserObject);
        } catch {
          reject(new Error("上传结果无法读取"));
        }
      },
      fail: (err) => reject(new Error(err.errMsg || "上传失败，请稍后重试")),
    });
  });
}

/**
 * Upload a picked file. Uses chunked sessions when the file is larger than the advised chunk size
 * and size is known; otherwise keeps the existing multipart path.
 * 已知大小且超过建议分片大小时走分片会话，否则仍走一次 multipart。
 */
export async function uploadObject(file: PickedFile): Promise<EndUserObject> {
  const size = await fileSize(file.filePath);
  if (size != null && size > MAX_UPLOAD_BYTES) {
    throw new PlatformHttpError("POST", "/api/v1/end-user/objects", 413, "", '{"reason":"object-upload-too-large"}');
  }
  const contentType = guessContentType(file.name) || guessContentType(file.filePath);
  if (size != null && size > DEFAULT_CHUNK_SIZE) {
    if (!contentType) {
      throw new PlatformHttpError(
        "POST",
        "/api/v1/end-user/objects/uploads",
        415,
        "",
        '{"reason":"object-content-type-not-allowed"}',
      );
    }
    return uploadChunked(file, size, contentType);
  }
  return uploadMultipart(file);
}

export async function issueDownloadLink(objectKey: string): Promise<IssuedDownloadLink> {
  const key = objectKey.trim();
  if (!key) throw new Error("请选择要打开的文件");
  return authedFetch<IssuedDownloadLink>({
    method: "POST",
    path: "/api/v1/end-user/objects/download-links",
    tenantId: true,
    body: { objectKey: key },
  });
}

/** Download the signed URL and open it — 下载签名地址并打开. */
export async function openObject(objectKey: string): Promise<void> {
  const link = await issueDownloadLink(objectKey);
  if (!link.url) throw new Error("暂时无法打开这个文件");
  await new Promise<void>((resolve, reject) => {
    uni.downloadFile({
      url: link.url,
      success: (res) => {
        if (res.statusCode && res.statusCode !== 200) {
          reject(new Error("下载失败，请稍后重试"));
          return;
        }
        uni.openDocument({
          filePath: res.tempFilePath,
          showMenu: true,
          success: () => resolve(),
          fail: () => reject(new Error("文件已下载，但当前环境无法直接打开")),
        });
      },
      fail: (err) => reject(new Error(err.errMsg || "下载失败，请稍后重试")),
    });
  });
}
