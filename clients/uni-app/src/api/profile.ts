/**
 * End-user profile — 终端资料。
 * GET/PATCH {@code /api/v1/end-user/profile}: display name, authorized-track avatar,
 * and email that is stored pending until a verification code is consumed.
 * 读写称呼、授权轨头像，以及须验证后才成为资料邮箱的地址。
 */

import { authedFetch } from "./platform";
import type { PickedFile } from "./objects";

export type IssuedAvatarDownload = {
  url: string;
  expiresAt?: string;
  ttl?: string;
  backend?: string;
};

export type EndUserProfile = {
  accountId?: string;
  subjectId?: string;
  subjectName?: string;
  avatarObjectId?: string | null;
  avatarDownload?: IssuedAvatarDownload | null;
  /** Verified address only — 仅已验证邮箱. */
  email?: string | null;
  emailVerifiedAt?: string | null;
  /** Not yet the profile email — 尚未成为资料邮箱. */
  pendingEmail?: string | null;
  emailVerified?: boolean;
};

export type RequestEmailCodeResponse = {
  expiresIn?: number;
  purpose?: string;
  /** Present only when the server is in a dev/local reveal mode — 仅本地/开发揭示。 */
  devCode?: string | null;
};

export async function getProfile(): Promise<EndUserProfile> {
  return authedFetch<EndUserProfile>({
    path: "/api/v1/end-user/profile",
    tenantId: true,
  });
}

export async function updateProfile(subjectName: string): Promise<EndUserProfile> {
  const name = subjectName.trim();
  if (!name) throw new Error("请填写您的姓名或昵称");
  return authedFetch<EndUserProfile>({
    method: "PATCH",
    path: "/api/v1/end-user/profile",
    tenantId: true,
    body: { subjectName: name },
  });
}

export async function setAvatar(avatarObjectId: string): Promise<EndUserProfile> {
  const id = avatarObjectId.trim();
  if (!id) throw new Error("请先选择一张照片");
  return authedFetch<EndUserProfile>({
    method: "PATCH",
    path: "/api/v1/end-user/profile",
    tenantId: true,
    body: { avatarObjectId: id },
  });
}

export async function clearAvatar(): Promise<EndUserProfile> {
  return authedFetch<EndUserProfile>({
    method: "DELETE",
    path: "/api/v1/end-user/profile/avatar",
    tenantId: true,
  });
}

export async function requestEmailCode(email: string): Promise<RequestEmailCodeResponse> {
  const address = email.trim();
  if (!address) throw new Error("请填写邮箱地址");
  return authedFetch<RequestEmailCodeResponse>({
    method: "POST",
    path: "/api/v1/end-user/profile/email/request-code",
    tenantId: true,
    body: { email: address },
  });
}

export async function verifyEmail(email: string, code: string): Promise<EndUserProfile> {
  const address = email.trim();
  const value = code.trim();
  if (!address) throw new Error("请填写邮箱地址");
  if (!value) throw new Error("请填写验证码");
  return authedFetch<EndUserProfile>({
    method: "POST",
    path: "/api/v1/end-user/profile/email/verify",
    tenantId: true,
    body: { email: address, code: value },
  });
}

/**
 * Pick one still image with the uni-app image picker (H5 and mini-program).
 * 用 uni 选图接口选一张照片（H5 与小程序）。
 */
export function pickAvatar(): Promise<PickedFile> {
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count: 1,
      sizeType: ["compressed", "original"],
      sourceType: ["album", "camera"],
      success: (res) => {
        const paths = res.tempFilePaths;
        const filePath = Array.isArray(paths) ? paths[0] : undefined;
        if (!filePath) {
          reject(new Error("没有选择图片"));
          return;
        }
        const files = res.tempFiles;
        const first = Array.isArray(files) ? files[0] : files;
        const named =
          first && typeof first === "object" && "name" in first
            ? String((first as { name?: string }).name || "")
            : "";
        resolve({ filePath, name: named || filePath });
      },
      fail: (err) => reject(new Error(err.errMsg || "没有选择图片")),
    });
  });
}
