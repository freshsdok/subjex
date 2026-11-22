package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.consent.ConsentEvent;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.consent.ConsentRecord;
import com.subjex.platform.contract.identity.Identity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserConsentEndpoint — WP8-1 own purpose-consent routes under {@code /api/v1/end-user/consents}.
 * 终端本人目的同意路由；只操作调用方自己的主体，本切片不加操作员路由。
 */
@RestController
public class EndUserConsentEndpoint {

    /** JSON base for end-user consents — 终端同意 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/consents";

    private final ConsentPort consents;
    private final EndUserAccountStore accounts;

    public EndUserConsentEndpoint(ConsentPort consents, EndUserAccountStore accounts) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @GetMapping(PATH)
    public List<CurrentDocument> listCurrent(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        Scope scope = scope(tenantHeader, tenantIdParam);
        return consents.listCurrent(scope.tenantId(), scope.subjectId()).stream()
                .map(CurrentDocument::from)
                .toList();
    }

    @PostMapping(PATH)
    public CurrentDocument grant(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody GrantRequest body) {
        Scope scope = scope(tenantHeader, tenantIdParam);
        String purpose = body == null ? null : body.purposeKey();
        String version = body == null ? null : body.consentVersionId();
        return CurrentDocument.from(
                consents.grant(scope.tenantId(), scope.subjectId(), purpose, version, scope.actorIdentityId()));
    }

    @PostMapping(PATH + "/{purposeKey}/withdraw")
    public HistoryDocument withdraw(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("purposeKey") String purposeKey) {
        Scope scope = scope(tenantHeader, tenantIdParam);
        return HistoryDocument.from(
                consents.withdraw(scope.tenantId(), scope.subjectId(), purposeKey, scope.actorIdentityId()));
    }

    @GetMapping(PATH + "/history")
    public List<HistoryDocument> history(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "purposeKey", required = false) String purposeKey) {
        Scope scope = scope(tenantHeader, tenantIdParam);
        return consents.history(scope.tenantId(), scope.subjectId(), purposeKey).stream()
                .map(HistoryDocument::from)
                .toList();
    }

    /**
     * Bind to the caller's own subject in the explicit business tenant.
     * 绑定到调用方在明确业务租户中的本人主体；无法指定他人。
     */
    private Scope scope(String tenantHeader, String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        Identity identity = accounts.requireActiveMembership(
                principal.accountId(), resolveTenant(tenantHeader, tenantIdParam));
        if (!principal.subjectId().equals(identity.subjectId())) {
            throw new EndUserTenantAccessDeniedException();
        }
        return new Scope(identity.tenantId(), identity.subjectId(), identity.identityId());
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }

    private record Scope(String tenantId, String subjectId, String actorIdentityId) {}

    /** Current grant JSON — 当前授予文档. */
    public record CurrentDocument(
            String tenantId,
            String subjectId,
            String purposeKey,
            String consentVersionId,
            String eventId,
            Instant grantedAt) {
        public static CurrentDocument from(ConsentRecord record) {
            return new CurrentDocument(
                    record.tenantId(),
                    record.subjectId(),
                    record.purposeKey(),
                    record.consentVersionId(),
                    record.eventId(),
                    record.grantedAt());
        }
    }

    /** History / withdraw JSON — 历史或撤销文档. */
    public record HistoryDocument(
            String eventId,
            String tenantId,
            String subjectId,
            String purposeKey,
            String consentVersionId,
            String eventKind,
            String actorIdentityId,
            Instant occurredAt) {
        public static HistoryDocument from(ConsentEvent event) {
            return new HistoryDocument(
                    event.eventId(),
                    event.tenantId(),
                    event.subjectId(),
                    event.purposeKey(),
                    event.consentVersionId(),
                    event.eventKind().name(),
                    event.actorIdentityId(),
                    event.occurredAt());
        }
    }

    /** Grant body: purpose + tenant-supplied version id only — 仅目的键与租户提供的版本标识. */
    public record GrantRequest(String purposeKey, String consentVersionId) {}
}
