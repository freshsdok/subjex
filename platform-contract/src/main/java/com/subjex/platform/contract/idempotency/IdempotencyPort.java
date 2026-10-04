package com.subjex.platform.contract.idempotency;

import java.util.Optional;

/**
 * IdempotencyPort — 幂等端口：平台唯一的重复提交判断入口。
 * <p>
 * There is one implementation. Do not add a second port beside it.
 * 只有一个实现。不要在旁边再加一个端口。
 */
public interface IdempotencyPort {

    /**
     * Look up a token already claimed by this tenant — 查找该租户已经占用过的记号。
     */
    Optional<IdempotencyClaim> find(String tenantId, String idempotencyToken);

    /**
     * Claim a token that {@link #find} showed was absent — 占用一枚此前不存在的记号。
     */
    void insert(IdempotencyClaim claim);
}
