package com.subjex.platform.contract.points;

/**
 * PointsDirection — grant increases the balance; deduct decreases it and refuses an overdraft.
 * 发放增加余额；扣减减少余额，余额不足则拒绝。
 */
public enum PointsDirection {
    GRANT,
    DEDUCT
}
