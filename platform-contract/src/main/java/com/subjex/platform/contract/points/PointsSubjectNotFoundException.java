package com.subjex.platform.contract.points;

/**
 * PointsSubjectNotFoundException — The subject has no ACTIVE identity in this tenant. / 该主体在本租户没有 ACTIVE 身份。
 */
public final class PointsSubjectNotFoundException extends RuntimeException {

    public static final String REASON = "points-subject-not-found";

    public PointsSubjectNotFoundException(String message) {
        super(message);
    }
}
