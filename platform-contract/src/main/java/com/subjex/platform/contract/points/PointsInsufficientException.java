package com.subjex.platform.contract.points;

/**
 * PointsInsufficientException — Deduct would overdraft the balance; this ledger refuses a negative balance. / 扣减会使余额透支；本台账拒绝负余额。
 */
public final class PointsInsufficientException extends RuntimeException {

    public static final String REASON = "points-insufficient";

    public PointsInsufficientException(String message) {
        super(message);
    }
}
