/**
 * End-user own points balance and history. No self-adjust.
 * 终端本人积分余额与历史。不能自行调整。
 */

import { authedFetch } from "./platform";

export type PointsLedgerEntry = {
  entryId: string;
  delta?: number;
  balanceAfter?: number;
  direction?: string;
  reason?: string;
  memo?: string | null;
  referenceType?: string | null;
  referenceId?: string | null;
  createdAt?: string;
};

export type PointsAccount = {
  tenantId?: string;
  subjectId?: string;
  balance?: number;
  entries?: PointsLedgerEntry[];
};

const POINTS_PATH = "/api/v1/end-user/points";

/** Own balance and newest-first history in the current tenant — 本组织本人余额与由新到旧的历史. */
export async function getOwnPoints(): Promise<PointsAccount> {
  return authedFetch<PointsAccount>({
    path: POINTS_PATH,
    tenantId: true,
  });
}
