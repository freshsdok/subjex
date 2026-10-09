package com.subjex.platform.app.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AesGcmSecretCipher — AES-256-GCM for small secrets at rest (TOTP seeds).
 * <p>
 * Key material is SHA-256 of a configured passphrase (≥ 32 characters recommended).
 * Wire format: {@code v1.} + base64url(iv || tag || ciphertext).
 * Encrypt uses the current key only. Decrypt tries current then optional previous (rotation overlap).
 * 用配置口令的 SHA-256 作密钥；密文形态 {@code v1.} + base64url(iv || 标签 || 密文)。
 * 加密只用当前密钥；解密先当前再可选旧密钥（轮换重叠期）。
 */
public final class AesGcmSecretCipher {

    private static final String PREFIX = "v1.";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] currentKeyBytes;
    private final List<byte[]> decryptKeyBytes;

    public AesGcmSecretCipher(String passphrase) {
        this(passphrase, null);
    }

    public AesGcmSecretCipher(String passphrase, String previousPassphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        if (passphrase.isBlank()) {
            throw new IllegalArgumentException("MFA encryption key must not be blank");
        }
        this.currentKeyBytes = digest(passphrase);
        List<byte[]> keys = new ArrayList<>(2);
        keys.add(this.currentKeyBytes);
        if (previousPassphrase != null && !previousPassphrase.isBlank()) {
            keys.add(digest(previousPassphrase));
        }
        this.decryptKeyBytes = List.copyOf(keys);
    }

    private static byte[] digest(String passphrase) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public String encryptUtf8(String plaintext) {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(currentKeyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(plaintext);
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherAndTag.length);
            buffer.put(iv);
            buffer.put(cipherAndTag);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encrypt failed", ex);
        }
    }

    public String decryptUtf8(String blob) {
        return new String(decrypt(blob), StandardCharsets.UTF_8);
    }

    public byte[] decrypt(String blob) {
        Objects.requireNonNull(blob, "blob");
        if (!blob.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported secret encryption version");
        }
        byte[] packed = Base64.getUrlDecoder().decode(blob.substring(PREFIX.length()));
        if (packed.length <= IV_LENGTH + TAG_BITS / 8) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
        byte[] cipherAndTag = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
        GeneralSecurityException last = null;
        for (byte[] keyBytes : decryptKeyBytes) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(TAG_BITS, iv));
                return cipher.doFinal(cipherAndTag);
            } catch (GeneralSecurityException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("AES-GCM decrypt failed", last);
    }
}
