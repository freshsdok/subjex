package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorSelfEndpoint — 操作员自述接口：当前登录的是谁、代表哪个主体、以哪个身份行动、持有哪些权限。
 * <p>
 * Permissions are sorted by name. The password hash is never returned.
 * 权限按名字排序。口令摘要从不返回。
 */
@RestController
public class OperatorSelfEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/me";

    private final JdbcOperatorMfaStore mfaStore;

    public OperatorSelfEndpoint(JdbcOperatorMfaStore mfaStore) {
        this.mfaStore = mfaStore;
    }

    @GetMapping(PATH)
    public OperatorSelfDocument me(@AuthenticationPrincipal OperatorPrincipal operator) {
        List<String> permissions = operator.permissionNames().stream().sorted().toList();
        return new OperatorSelfDocument(
                operator.getUsername(),
                operator.identityId(),
                operator.subjectId(),
                permissions,
                mfaStore.isEnrolled(operator.subjectId()),
                mfaStore.enrollmentRequired(operator));
    }

    /**
     * OperatorSelfDocument — 操作员自述：登录名、身份、主体、权限、是否已登记 MFA、策略是否强制登记。
     */
    public record OperatorSelfDocument(
            String loginName,
            String identityId,
            String subjectId,
            List<String> permissions,
            boolean mfaEnrolled,
            boolean mfaEnrollmentRequired) {}
}
