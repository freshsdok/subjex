package com.subjex.platform.app.tenant;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantManagementEndpoint;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TenantDataLifecycleEndpoint — TN-2 export ZIP + erase challenge/confirm ({@code tenant.manage}).
 * 租户导出 ZIP 与硬删除挑战/确认（需 tenant.manage）。
 */
@RestController
public class TenantDataLifecycleEndpoint {

    public static final String EXPORT_PATH = TenantManagementEndpoint.PATH + "/{tenantId}/export";
    public static final String ERASE_CHALLENGE_PATH = TenantManagementEndpoint.PATH + "/{tenantId}/erase/challenge";
    public static final String ERASE_PATH = TenantManagementEndpoint.PATH + "/{tenantId}/erase";

    private final TenantDataLifecycleService lifecycle;

    public TenantDataLifecycleEndpoint(TenantDataLifecycleService lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @PostMapping(JsonApi.BASE + "/tenants/{tenantId}/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable("tenantId") String tenantId) {
        requireOperator(operator);
        try {
            byte[] zip = lifecycle.exportZip(operator, tenantId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tenant-" + tenantId + "-export.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping(JsonApi.BASE + "/tenants/{tenantId}/erase/challenge")
    public EraseChallengeDocument eraseChallenge(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable("tenantId") String tenantId) {
        requireOperator(operator);
        try {
            var issued = lifecycle.issueEraseChallenge(operator, tenantId);
            return new EraseChallengeDocument(issued.challengeId(), issued.token(), issued.expiresAt());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping(JsonApi.BASE + "/tenants/{tenantId}/erase")
    public EraseResultDocument erase(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("tenantId") String tenantId,
            @RequestBody EraseConfirmRequest body) {
        requireOperator(operator);
        try {
            var result = lifecycle.erase(
                    operator,
                    tenantId,
                    body == null ? null : body.challengeId(),
                    body == null ? null : body.token());
            return new EraseResultDocument(
                    result.tenantId(),
                    result.counts().totalRows(),
                    result.objectStorageDeletes(),
                    result.counts());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static ResponseStatusException badRequest(IllegalArgumentException ex) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    public record EraseChallengeDocument(String challengeId, String token, Instant expiresAt) {}

    public record EraseConfirmRequest(String challengeId, String token) {}

    public record EraseResultDocument(
            String tenantId,
            int rowsDeleted,
            int objectStorageDeletes,
            TenantDataLifecycleService.EraseCounts counts) {}
}
