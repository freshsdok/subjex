package com.subjex.platform.app.storage;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.storage.StoredObject;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ObjectDownloadEndpoint — thin issue / local-redeem API for signed downloads (WP3-2).
 * 签发与本地兑换的薄 API。操作员与终端分路径，不混用壳层。
 * <p>
 * Operator issue: {@code POST /api/v1/objects/download-links} (operator principal only;
 * not on the end-user realm-gate allow-list). End-user issue:
 * {@code POST /api/v1/end-user/objects/download-links} (already under {@code /api/v1/end-user/}).
 * Local redeem: {@code GET /api/v1/end-user/objects/content} is {@code permitAll}; the signature
 * is the credential, so a browser (or an attached end-user Bearer) can fetch bytes.
 * 操作员签发不开放给终端令牌；终端签发走已放行的 {@code /api/v1/end-user/} 前缀。
 * 本地兑换按签名放行，无需再扩展操作员管理面允许列表。
 */
@RestController
public class ObjectDownloadEndpoint {

    /** Operator console issue path — 操作员控制台签发路径。 */
    public static final String OPERATOR_ISSUE_PATH = JsonApi.BASE + "/objects/download-links";

    /** End-user issue path (realm-gate allow-listed prefix) — 终端签发路径。 */
    public static final String END_USER_ISSUE_PATH = JsonApi.BASE + "/end-user/objects/download-links";

    /** Local-disk redeem path (signature is the credential) — 本地盘兑换路径（签名即凭证）。 */
    public static final String REDEEM_PATH = JsonApi.BASE + "/end-user/objects/content";

    private final ObjectDownloadLinkService downloads;

    public ObjectDownloadEndpoint(ObjectDownloadLinkService downloads) {
        this.downloads = downloads;
    }

    @PostMapping(path = OPERATOR_ISSUE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public IssuedDownloadLink issueForOperator(
            Authentication authentication,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody IssueRequest body) {
        if (!(principalOf(authentication) instanceof OperatorPrincipal operator)) {
            throw new AccessDeniedException("operator-authentication-required");
        }
        return downloads.issue(
                resolveTenant(body, tenantHeader),
                requireObjectKey(body),
                operator.subjectId(),
                parseTtl(body));
    }

    @PostMapping(path = END_USER_ISSUE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public IssuedDownloadLink issueForEndUser(
            Authentication authentication,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody IssueRequest body) {
        if (!(principalOf(authentication) instanceof EndUserPrincipal endUser) || !endUser.active()) {
            throw new AccessDeniedException("end-user-authentication-required");
        }
        return downloads.issue(
                resolveTenant(body, tenantHeader),
                requireObjectKey(body),
                endUser.subjectId(),
                parseTtl(body));
    }

    @GetMapping(path = REDEEM_PATH)
    public ResponseEntity<byte[]> redeem(
            @RequestParam String tenantId,
            @RequestParam String objectKey,
            @RequestParam("expires") long expires,
            @RequestParam String sig) {
        StoredObject object = downloads
                .redeemLocal(tenantId, objectKey, expires, sig)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MediaType type = mediaType(object.mediaType());
        return ResponseEntity.ok().contentType(type).body(object.content());
    }


    private static Object principalOf(Authentication authentication) {
        Authentication auth = authentication;
        if (auth == null) {
            // Standalone tests (and some filters) set the principal only on the SecurityContext.
            // 独立测试或某些过滤器只把主体放在 SecurityContext 上。
            auth = SecurityContextHolder.getContext().getAuthentication();
        }
        return auth == null ? null : auth.getPrincipal();
    }

    private static String resolveTenant(IssueRequest body, String tenantHeader) {
        String fromBody = body == null ? null : trimToNull(body.tenantId());
        String fromHeader = trimToNull(tenantHeader);
        if (fromBody != null && fromHeader != null && !fromBody.equals(fromHeader)) {
            throw new IllegalArgumentException("tenantId in body and X-Tenant-Id do not match");
        }
        String tenant = fromBody != null ? fromBody : fromHeader;
        if (tenant == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenant;
    }

    private static String requireObjectKey(IssueRequest body) {
        String key = body == null ? null : trimToNull(body.objectKey());
        if (key == null) {
            throw new IllegalArgumentException("objectKey is required");
        }
        return key;
    }

    private static Duration parseTtl(IssueRequest body) {
        if (body == null || body.ttl() == null || body.ttl().isBlank()) {
            return null;
        }
        String raw = body.ttl().trim();
        try {
            if (raw.startsWith("PT") || raw.startsWith("pt") || raw.startsWith("P") || raw.startsWith("p")) {
                return Duration.parse(raw.toUpperCase(Locale.ROOT));
            }
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ttl must be an ISO-8601 duration or a second count");
        }
    }

    private static MediaType mediaType(String raw) {
        if (raw == null || raw.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(raw);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Issue request body — 签发请求体。{@code ttl} is ISO-8601 or seconds; omit for the default.
     * {@code ttl} 为 ISO-8601 或秒数；省略则用默认 PT15M。
     */
    public record IssueRequest(String tenantId, String objectKey, String ttl) {}
}
