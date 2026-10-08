package com.subjex.platform.app.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TotpGenerator — RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s step) plus Base32 helpers.
 * <p>
 * Minimal implementation — no third-party OTP library.
 * 最小实现：不引入第三方 OTP 库。
 */
public final class TotpGenerator {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpGenerator() {}

    /** Random Base32 secret (no padding) — 随机 Base32 密钥（无填充）。 */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public static String otpauthUri(String issuer, String accountName, String secretBase32) {
        String label = URLEncoder.encode(issuer + ":" + accountName, StandardCharsets.UTF_8).replace("+", "%20");
        String issuerParam = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/"
                + label
                + "?secret="
                + secretBase32
                + "&issuer="
                + issuerParam
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + PERIOD_SECONDS;
    }

    /**
     * Accept the code if it matches the current step or ±{@ steps.
     * 当前步或前后 window 步内匹配即接受。
     */
    public static boolean verify(String secretBase32, String code, Instant now, int window) {
        if (secretBase32 == null || code == null) {
            return false;
        }
        String normalized = code.trim().replace(" ", "");
        if (!normalized.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long step = now.getEpochSecond() / PERIOD_SECONDS;
        byte[] key = decodeBase32(secretBase32);
        for (int delta = -window; delta <= window; delta++) {
            if (normalized.equals(formatCode(hotp(key, step + delta)))) {
                return true;
            }
        }
        return false;
    }

    /** Current TOTP code (tests / diagnostics) — 当前码（测试/诊断）。 */
    public static String currentCode(String secretBase32, Instant now) {
        long step = now.getEpochSecond() / PERIOD_SECONDS;
        return formatCode(hotp(decodeBase32(secretBase32), step));
    }

    static int hotp(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary =
                    ((hash[offset] & 0x7f) << 24)
                            | ((hash[offset + 1] & 0xff) << 16)
                            | ((hash[offset + 2] & 0xff) << 8)
                            | (hash[offset + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < DIGITS; i++) {
                mod *= 10;
            }
            return binary % mod;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA1 not available", ex);
        }
    }

    private static String formatCode(int value) {
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", value);
    }

    static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String encoded) {
        String cleaned = encoded.trim().toUpperCase(Locale.ROOT).replace("=", "").replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("empty Base32 secret");
        }
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        for (int i = 0; i < cleaned.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(cleaned.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("invalid Base32 character");
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        if (count == out.length) {
            return out;
        }
        return java.util.Arrays.copyOf(out, count);
    }
}
