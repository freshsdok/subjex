package com.subjex.platform.contract.points;

/**
 * PointsPort — tenant-scoped ledger reads and operator adjustments. No earning or redemption.
 * 按租户查阅台账并由操作员调整。不发放、不兑换。
 */
public interface PointsPort {

    /**
     * Balance and newest-first history for a subject that already has an ACTIVE identity in the tenant.
     * 查阅在该租户已有 ACTIVE 身份的主体余额与由新到旧的历史。
     * <p>
     * A subject with no ledger row yet returns balance 0 and an empty history — it is not created here.
     * 尚无台账行的主体返回余额 0 与空历史——本方法不会插入账户。
     *
     * @throws PointsSubjectNotFoundException if the subject has no ACTIVE identity in the tenant
     *     主体在该租户没有 ACTIVE 身份时抛出
     */
    PointsAccountDocument getAccount(String tenantId, String subjectId, int limit);

    /**
     * Grant or deduct. First grant creates the account; deduct with no account or a shortfall is refused.
     * 发放或扣减。首次发放会创建账户；无账户或余额不足的扣减会被拒绝。
     */
    PointsAccountDocument adjust(AdjustPointsCommand command);
}
