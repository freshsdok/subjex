/**
 * End-user profile (WP7-3) — 终端资料。
 * GET/PATCH {@code /api/v1/end-user/profile} (display name only).
 * 只读写展示名。
 */

import { authedFetch } from "./platform";

export type EndUserProfile = {
  accountId?: string;
  subjectId?: string;
  subjectName?: string;
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
