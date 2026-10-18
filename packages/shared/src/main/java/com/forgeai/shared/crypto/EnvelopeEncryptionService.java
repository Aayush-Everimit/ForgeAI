package com.forgeai.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption service for BYOK provider credentials.
 * Generates a unique 256-bit Data Encryption Key (DEK) per credential, encrypts the plaintext key
 * with AES-256-GCM, and wraps the DEK using the configured Key Encryption Key (KEK).
 * Never logs plaintext keys.
 */
@Service
public class EnvelopeEncryptionService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey kek;
    private final String kekKeyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public EnvelopeEncryptionService(
            @Value("${forgeai.security.kek-secret:local-dev-kek-secret-change-in-production-32b}") String kekSecret,
            @Value("${forgeai.security.kek-key-id:kek-v1}") String kekKeyId
    ) {
        this.kek = derive256BitKey(kekSecret);
        this.kekKeyId = kekKeyId;
    }

    public EncryptedPayload encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Plaintext credential cannot be blank");
        }
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, secureRandom);
            SecretKey dek = keyGen.generateKey();

            byte[] ciphertextBytes = aesGcmEncrypt(dek, plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] wrappedDekBytes = aesGcmEncrypt(kek, dek.getEncoded());

            return new EncryptedPayload(
                    Base64.getEncoder().encodeToString(ciphertextBytes),
                    Base64.getEncoder().encodeToString(wrappedDekBytes),
                    kekKeyId
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential material", e);
        }
    }

    public String decrypt(String ciphertextBase64, String wrappedDekBase64) {
        try {
            byte[] wrappedDekBytes = Base64.getDecoder().decode(wrappedDekBase64);
            byte[] dekBytes = aesGcmDecrypt(kek, wrappedDekBytes);
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");

            byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertextBase64);
            byte[] plaintextBytes = aesGcmDecrypt(dek, ciphertextBytes);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential material", e);
        }
    }

    public static String maskKeyHint(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private byte[] aesGcmEncrypt(SecretKey key, byte[] plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return buffer.array();
    }

    private byte[] aesGcmDecrypt(SecretKey key, byte[] ivAndCiphertext) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(ivAndCiphertext);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey derive256BitKey(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize KEK", e);
        }
    }

    public record EncryptedPayload(String ciphertext, String wrappedDek, String kekKeyId) {}
}
