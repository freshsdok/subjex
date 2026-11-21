/**
 * End-user objects (WP7-3) — 终端文件。
 * List/upload {@code /api/v1/end-user/objects}; download via signed links.
 * 列表与上传走终端对象接口；打开文件走已有签名下载。
 */

import { apiBase, authedFetch, PlatformHttpError } from "./platform";
import { readBearer } from "./auth-token";
import { TENANT_HEADER, readTenantId, requireBusinessTenantId } from "./tenant";

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

/** Multipart upload; field name must be {@code file}. / 分件上传，字段名必须是 file. */
export async function uploadObject(file: PickedFile): Promise<EndUserObject> {
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
