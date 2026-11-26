package com.subjex.platform.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TenantKeysetTest — clamp, encode/decode, and next-cursor rules for the shared keyset helper.
 * 共享键集助手的条数限制、编解码与下一游标规则。
 */
class TenantKeysetTest {

    @Test
    void clampEncodeDecodeAndNextCursor() {
        assertThat(TenantKeyset.clamp(null)).isEqualTo(TenantKeyset.DEFAULT_LIMIT);
        assertThat(TenantKeyset.clamp(0)).isEqualTo(TenantKeyset.DEFAULT_LIMIT);
        assertThat(TenantKeyset.clamp(1000)).isEqualTo(TenantKeyset.MAX_LIMIT);
        assertThat(TenantKeyset.clamp(7)).isEqualTo(7);

        Instant at = Instant.parse("2026-11-26T12:00:00Z");
        String cursor = TenantKeyset.encode(at, "req-1");
        TenantKeyset.Cursor decoded = TenantKeyset.decode(cursor);
        assertThat(decoded.instant()).isEqualTo(at);
        assertThat(decoded.id()).isEqualTo("req-1");
        assertThat(TenantKeyset.decode("not-a-cursor")).isNull();
        assertThat(TenantKeyset.decode("")).isNull();

        assertThat(TenantKeyset.nextCursor(List.of("a"), 2, item -> item)).isNull();
        assertThat(TenantKeyset.nextCursor(List.of("a", "b"), 2, item -> TenantKeyset.encode(at, item)))
                .isEqualTo(TenantKeyset.encode(at, "b"));
    }
}
