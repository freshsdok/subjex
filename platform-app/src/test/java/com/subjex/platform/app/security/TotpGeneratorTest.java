package com.subjex.platform.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpGeneratorTest {

    @Test
    void roundTripSecretAndVerifyWindow() {
        String secret = TotpGenerator.generateSecret();
        assertThat(secret).isNotBlank();
        Instant now = Instant.parse("2026-10-08T08:00:00Z");
        String code = TotpGenerator.currentCode(secret, now);
        assertThat(code).hasSize(6);
        assertThat(TotpGenerator.verify(secret, code, now, 1)).isTrue();
        assertThat(TotpGenerator.verify(secret, "000000", now, 1)).isFalse();
        String uri = TotpGenerator.otpauthUri("subjex", "alice", secret);
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=" + secret);
    }
}
