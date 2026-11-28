package com.subjex.platform.app.points;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.points.PointsAccountDocument;
import com.subjex.platform.contract.points.PointsPort;
import java.util.Objects;

/**
 * EndUserPointsService — the signed-in end user can read only their own balance and history.
 * 已登录终端用户只能查阅本人余额与历史；不能调整。
 */
public final class EndUserPointsService {

    private final PointsPort points;
    private final EndUserAccountStore accounts;

    public EndUserPointsService(PointsPort points, EndUserAccountStore accounts) {
        this.points = Objects.requireNonNull(points, "points");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /**
     * Own balance and newest-first history in the current membership tenant.
     * 当前成员租户内本人的余额与由新到旧的历史。不能改成别人的主体。
     */
    public PointsAccountDocument getOwn(EndUserPrincipal principal, String tenantId, Integer limit) {
        Identity identity = requireMembership(principal, tenantId);
        return points.getAccount(identity.tenantId(), identity.subjectId(), limit == null ? 0 : limit);
    }

    private Identity requireMembership(EndUserPrincipal principal, String tenantId) {
        Objects.requireNonNull(principal, "principal");
        return accounts.requireActiveMembership(principal.accountId(), tenantId);
    }
}
