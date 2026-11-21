package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ObjectDownloadLinkServiceTest — WP3-2 signed download issued only after ALLOW (ADR 0015 D3).
 * 目的：所有者/授权主体/ACTIVE 组织成员可得到 URL；无授权、过期/撤销、跨租户得不到 URL；
 * TTL 默认 PT15M、硬上限 PT1H；S3 路径给出预签名 GET（假签发器）；本地 HMAC 可兑换，过期或篡改拒绝。
 */
class ObjectDownloadLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String KEY = "object-one";
    private static final String OWNER = "subject-owner";
    private static final String OTHER = "subject-other";
    private static final String MEMBER = "subject-member";
    private static final String ORG = "org-one";
    private static final byte[] BYTES = "payload".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void ownerGetsALocalUrl(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.storage.put(TENANT_A, KEY, BYTES, "text/plain");

        IssuedDownloadLink link = h.service.issue(TENANT_A, KEY, OWNER, null);
        assertEquals(IssuedDownloadLink.BACKEND_LOCAL, link.backend());
        assertEquals(Duration.parse("PT15M"), link.ttl());
        assertEquals(NOW.plus(Duration.parse("PT15M")), link.expiresAt());
        assertTrue(link.url().startsWith("http://127.0.0.1:8080" + ObjectDownloadEndpoint.REDEEM_PATH));
        assertTrue(link.url().contains("tenantId=" + TENANT_A));
        assertTrue(link.url().contains("objectKey=" + KEY));
        assertEquals(1, h.audit.size());
        assertEquals(OWNER, h.audit.get(0).actor());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void subjectGranteeGetsAUrl(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.store.grantToSubject(TENANT_A, KEY, OTHER, null, OWNER);
        IssuedDownloadLink link = h.service.issue(TENANT_A, KEY, OTHER, Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(10), link.ttl());
        assertFalse(link.url().isBlank());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void orgGranteeWithActiveMembershipGetsAUrl(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.orgs.upsertOrganization(ORG, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
        h.orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.subject(MEMBER);
        h.orgs.upsertMembership(MEMBER, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.store.grantToOrganization(TENANT_A, KEY, ORG, null, OWNER);
        IssuedDownloadLink link = h.service.issue(TENANT_A, KEY, MEMBER, null);
        assertFalse(link.url().isBlank());
        assertEquals(IssuedDownloadLink.BACKEND_LOCAL, link.backend());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void strangerWithoutGrantIsDeniedAndGetsNoUrl(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        ObjectDownloadDeniedException denied = assertThrows(
                ObjectDownloadDeniedException.class,
                () -> h.service.issue(TENANT_A, KEY, OTHER, null));
        assertEquals(ObjectDownloadDeniedException.REASON, denied.getMessage());
        assertTrue(h.audit.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void expiredAndRevokedGrantsAreDenied(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.store.grantToSubject(TENANT_A, KEY, OTHER, NOW.plusSeconds(30), OWNER);
        assertFalse(h.service.issue(TENANT_A, KEY, OTHER, null).url().isBlank());

        ObjectDownloadLinkService later = h.serviceWithClock(Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        assertThrows(
                ObjectDownloadDeniedException.class, () -> later.issue(TENANT_A, KEY, OTHER, null));

        h.store.grantToSubject(TENANT_A, KEY, MEMBER, null, OWNER);
        String grantId = h.store.listGrants(TENANT_A, KEY).stream()
                .filter(g -> MEMBER.equals(g.granteeId()))
                .findFirst()
                .orElseThrow()
                .grantId();
        h.store.revoke(grantId);
        assertThrows(
                ObjectDownloadDeniedException.class, () -> h.service.issue(TENANT_A, KEY, MEMBER, null));
        assertTrue(h.audit.size() >= 1);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void crossTenantIsDeniedAndGetsNoUrl(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        assertThrows(
                ObjectDownloadDeniedException.class, () -> h.service.issue(TENANT_B, KEY, OWNER, null));
        h.store.grantToSubject(TENANT_A, KEY, OTHER, null, OWNER);
        assertThrows(
                ObjectDownloadDeniedException.class, () -> h.service.issue(TENANT_B, KEY, OTHER, null));
        assertTrue(h.audit.isEmpty());
    }

    @Test
    void ttlDefaultIsFifteenMinutesAndHardCapIsOneHour(@TempDir Path root) {
        Harness h = Harness.open(H2PlatformTables.Mode.POSTGRESQL, root);
        h.store.register(TENANT_A, KEY, OWNER);
        IssuedDownloadLink def = h.service.issue(TENANT_A, KEY, OWNER, null);
        assertEquals(Duration.parse("PT15M"), def.ttl());

        IssuedDownloadLink hour = h.service.issue(TENANT_A, KEY, OWNER, Duration.parse("PT1H"));
        assertEquals(Duration.parse("PT1H"), hour.ttl());

        IllegalArgumentException over = assertThrows(
                IllegalArgumentException.class,
                () -> h.service.issue(TENANT_A, KEY, OWNER, Duration.parse("PT2H")));
        assertTrue(over.getMessage().contains("PT1H"));

        // A configured ttl-max above PT1H still cannot raise the hard cap.
        // 配置把 ttl-max 设到 PT1H 以上也不能抬高硬上限。
        ObjectDownloadProperties tight = new ObjectDownloadProperties(
                Duration.parse("PT15M"), Duration.parse("PT2H"), "http://127.0.0.1:8080", "test-hmac-key");
        assertEquals(Duration.parse("PT1H"), tight.maxTtl());
        assertThrows(IllegalArgumentException.class, () -> tight.resolveTtl(Duration.parse("PT2H")));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void s3PathProducesAPresignedGet(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        AtomicReference<String> seenKey = new AtomicReference<>();
        AtomicReference<Duration> seenTtl = new AtomicReference<>();
        S3GetObjectPresigner fake = (bucket, key, ttl) -> {
            seenKey.set(bucket + "/" + key);
            seenTtl.set(ttl);
            return URI.create(
                    "https://s3.test/" + bucket + "/" + key + "?X-Amz-Expires=" + ttl.toSeconds()
                            + "&X-Amz-Signature=test");
        };
        S3ObjectStorage s3 = new S3ObjectStorage(
                org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class),
                "subjex-blobs",
                fake);
        ObjectDownloadLinkService s3Service = h.serviceWithIssuer(new S3PresignedDownloadIssuer(s3, CLOCK), s3);
        IssuedDownloadLink link = s3Service.issue(TENANT_A, KEY, OWNER, null);
        assertEquals(IssuedDownloadLink.BACKEND_S3, link.backend());
        assertEquals(
                "https://s3.test/subjex-blobs/tenant-a/object-one?X-Amz-Expires=900&X-Amz-Signature=test",
                link.url());
        assertEquals("subjex-blobs/tenant-a/object-one", seenKey.get());
        assertEquals(Duration.parse("PT15M"), seenTtl.get());

        assertThrows(
                ObjectDownloadDeniedException.class, () -> s3Service.issue(TENANT_A, KEY, OTHER, null));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void localSignedUrlCanBeRedeemedAndRejectsExpiredOrTampered(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.storage.put(TENANT_A, KEY, BYTES, "text/plain");
        IssuedDownloadLink link = h.service.issue(TENANT_A, KEY, OWNER, Duration.ofMinutes(5));
        RedeemParts parts = RedeemParts.parse(link.url());
        Optional<StoredObject> found = h.service.redeemLocal(parts.tenantId, parts.objectKey, parts.expires, parts.sig);
        assertTrue(found.isPresent());
        assertArrayEquals(BYTES, found.get().content());
        assertEquals("text/plain", found.get().mediaType());

        assertThrows(
                ObjectDownloadInvalidException.class,
                () -> h.service.redeemLocal(parts.tenantId, parts.objectKey, parts.expires, "deadbeef"));
        assertThrows(
                ObjectDownloadInvalidException.class,
                () -> h.service.redeemLocal(parts.tenantId, "other-key", parts.expires, parts.sig));

        ObjectDownloadLinkService later = h.serviceWithClock(Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        assertThrows(
                ObjectDownloadInvalidException.class,
                () -> later.redeemLocal(parts.tenantId, parts.objectKey, parts.expires, parts.sig));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void deniedActorCannotObtainAUrlToRedeem(H2PlatformTables.Mode mode, @TempDir Path root) {
        Harness h = Harness.open(mode, root);
        h.store.register(TENANT_A, KEY, OWNER);
        h.storage.put(TENANT_A, KEY, BYTES, "text/plain");
        assertThrows(
                ObjectDownloadDeniedException.class, () -> h.service.issue(TENANT_A, KEY, OTHER, null));
        // Even a well-formed signature minted outside issue() is not how actors get a URL;
        // issue remains the authorization gate. Redeem still requires a valid signature.
        // 未授权主体不能经 issue 拿到 URL；判定关口在签发。兑换仍要求有效签名。
        IssuedDownloadLink ownerLink = h.service.issue(TENANT_A, KEY, OWNER, null);
        RedeemParts parts = RedeemParts.parse(ownerLink.url());
        // A denied actor does not get their own URL; possessing an already-issued link is the
        // same transferable property as an S3 presigned GET (authorization was at issue time).
        // 被拒绝的主体拿不到自己的 URL；已签发链接与 S3 预签名 GET 一样在有效期内可转让。
        Optional<StoredObject> redeemed = h.service.redeemLocal(parts.tenantId, parts.objectKey, parts.expires, parts.sig);
        assertTrue(redeemed.isPresent());
    }

    private static final class RedeemParts {
        final String tenantId;
        final String objectKey;
        final long expires;
        final String sig;

        private RedeemParts(String tenantId, String objectKey, long expires, String sig) {
            this.tenantId = tenantId;
            this.objectKey = objectKey;
            this.expires = expires;
            this.sig = sig;
        }

        static RedeemParts parse(String url) {
            URI uri = URI.create(url);
            String query = uri.getQuery();
            String tenantId = null;
            String objectKey = null;
            Long expires = null;
            String sig = null;
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String k = part.substring(0, eq);
                String v = java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                switch (k) {
                    case "tenantId" -> tenantId = v;
                    case "objectKey" -> objectKey = v;
                    case "expires" -> expires = Long.parseLong(v);
                    case "sig" -> sig = v;
                    default -> {
                    }
                }
            }
            return new RedeemParts(tenantId, objectKey, expires, sig);
        }
    }

    private record AuditCall(String tenantId, String objectKey, String actor, Instant expiresAt) {}

    private static final class Harness {
        final JdbcTemplate jdbc;
        final JdbcObjectGrantStore store;
        final ObjectAccessAuthorizer auth;
        final JdbcOrganizationStore orgs;
        final LocalDirectoryObjectStorage storage;
        final LocalDownloadSignature signature;
        final ObjectDownloadProperties properties;
        final List<AuditCall> audit = new ArrayList<>();
        final ObjectDownloadLinkService service;

        private Harness(JdbcTemplate jdbc, Path root) {
            this.jdbc = jdbc;
            this.store = new JdbcObjectGrantStore(jdbc, CLOCK);
            this.auth = new ObjectAccessAuthorizer(store, jdbc, CLOCK);
            this.orgs = new JdbcOrganizationStore(jdbc);
            this.storage = new LocalDirectoryObjectStorage(root);
            this.signature = new LocalDownloadSignature("test-hmac-key");
            this.properties = new ObjectDownloadProperties(
                    ObjectDownloadProperties.DEFAULT_TTL,
                    ObjectDownloadProperties.HARD_CAP,
                    "http://127.0.0.1:8080",
                    "test-hmac-key");
            this.service = newService(CLOCK, new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), CLOCK), storage);
        }

        static Harness open(H2PlatformTables.Mode mode, Path root) {
            DataSource ds = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    "Tenant A");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_B,
                    "Tenant B");
            return new Harness(jdbc, root);
        }

        ObjectDownloadLinkService serviceWithClock(Clock clock) {
            ObjectAccessAuthorizer laterAuth = new ObjectAccessAuthorizer(store, jdbc, clock);
            LocalHmacDownloadIssuer issuer =
                    new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock);
            return new ObjectDownloadLinkService(
                    laterAuth,
                    issuer,
                    signature,
                    storage,
                    properties,
                    clock,
                    (tenantId, objectKey, actor, expiresAt) ->
                            audit.add(new AuditCall(tenantId, objectKey, actor, expiresAt)));
        }

        ObjectDownloadLinkService serviceWithIssuer(ObjectDownloadUrlIssuer issuer, ObjectStorage objects) {
            return new ObjectDownloadLinkService(
                    auth,
                    issuer,
                    signature,
                    objects,
                    properties,
                    CLOCK,
                    (tenantId, objectKey, actor, expiresAt) ->
                            audit.add(new AuditCall(tenantId, objectKey, actor, expiresAt)));
        }

        private ObjectDownloadLinkService newService(
                Clock clock, ObjectDownloadUrlIssuer issuer, ObjectStorage objects) {
            return new ObjectDownloadLinkService(
                    auth,
                    issuer,
                    signature,
                    objects,
                    properties,
                    clock,
                    (tenantId, objectKey, actor, expiresAt) ->
                            audit.add(new AuditCall(tenantId, objectKey, actor, expiresAt)));
        }

        void subject(String subjectId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject WHERE subject_id = ?", Integer.class, subjectId);
            if (n != null && n > 0) {
                return;
            }
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    subjectId,
                    subjectId);
        }
    }
}
