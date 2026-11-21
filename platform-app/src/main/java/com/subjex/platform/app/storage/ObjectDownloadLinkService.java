package com.subjex.platform.app.storage;

import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * ObjectDownloadLinkService — issue a signed download URL only after {@link ObjectAccessAuthorizer}
 * returns {@link ObjectAccessDecision#ALLOW}; otherwise fail closed with no URL (WP3-2 / ADR 0015 D3).
 * 仅在访问判定为允许时签发短时签名下载；否则失败关闭，不返回可用 URL。
 * <p>
 * Both the S3 and local-disk backends go through the same authorizer first.
 * Audit is recorded at issue time in WP3-4; this slice only invokes a marked hook.
 * S3 与本地盘都先走同一判定。审计在 WP3-4 于签发时记录；本切片只调用已标明的钩子。
 */
public final class ObjectDownloadLinkService {

    private final ObjectAccessAuthorizer authorizer;
    private final ObjectDownloadUrlIssuer issuer;
    private final LocalDownloadSignature signature;
    private final ObjectStorage storage;
    private final ObjectDownloadProperties properties;
    private final Clock clock;
    private final ObjectDownloadIssueAuditHook auditHook;

    public ObjectDownloadLinkService(
            ObjectAccessAuthorizer authorizer,
            ObjectDownloadUrlIssuer issuer,
            LocalDownloadSignature signature,
            ObjectStorage storage,
            ObjectDownloadProperties properties,
            Clock clock,
            ObjectDownloadIssueAuditHook auditHook) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.auditHook = auditHook == null ? (t, k, a, e) -> {} : auditHook;
    }

    /**
     * Authorize the actor, then issue a short-lived URL. Denied actors get no URL.
     * 先判定再签发；被拒绝的主体得不到 URL。
     */
    public IssuedDownloadLink issue(
            String tenantId, String objectKey, String actorSubjectId, Duration requestedTtl) {
        Duration ttl = properties.resolveTtl(requestedTtl);
        if (authorizer.decide(tenantId, objectKey, actorSubjectId) != ObjectAccessDecision.ALLOW) {
            // No URL. Optional refused audit (real hook records; test stubs may no-op).
            // 不签发 URL。拒绝审计由真实钩子记录；测试桩可为空操作。
            auditHook.afterDeniedIssue(tenantId, objectKey, actorSubjectId);
            throw new ObjectDownloadDeniedException();
        }
        IssuedDownloadLink link = issuer.issue(tenantId, objectKey, ttl);
        // Audit at "authorized and issued" time (not at S3 GET / local redeem).
        // 在「已授权并签发」时记审计（不是 S3 GET / 本地兑换时）。
        auditHook.afterAuthorizedIssue(tenantId, objectKey, actorSubjectId, link.expiresAt());
        return link;
    }

    /**
     * Verify a local HMAC URL and stream the stored bytes. Does not re-run the authorizer
     * (S3 GET also would not). Expired or tampered tokens are rejected; missing bytes are empty.
     * 核验本地 HMAC URL 并取出字节。不重新判定（S3 GET 也不会再进应用）。
     * 过期或被篡改拒绝；签名有效但对象缺失则返回空。
     */
    public Optional<StoredObject> redeemLocal(
            String tenantId, String objectKey, long expiresEpochSeconds, String sig) {
        if (tenantId == null
                || tenantId.isBlank()
                || objectKey == null
                || objectKey.isBlank()
                || sig == null
                || sig.isBlank()) {
            throw new ObjectDownloadInvalidException();
        }
        Instant expiresAt = Instant.ofEpochSecond(expiresEpochSeconds);
        if (!signature.matches(tenantId, objectKey, expiresAt, sig)) {
            throw new ObjectDownloadInvalidException();
        }
        if (clock.instant().isAfter(expiresAt)) {
            throw new ObjectDownloadInvalidException();
        }
        return storage.find(tenantId, objectKey);
    }
}
