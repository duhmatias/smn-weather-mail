package ar.gob.smn.weather;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Encrypts/decrypts property secrets with a user passphrase ({@code SMN_MASTER_PASSWORD}).
 * Wrapped values use prefix {@code ENC1:} plus Base64(salt || iv || ciphertext+tag).
 */
final class PropertySecretCipher {

    private static final String PREFIX = "ENC1:";
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 100_000;

    private PropertySecretCipher() {}

    static boolean isWrapped(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static String encrypt(String plaintext, String masterPassword) throws GeneralSecurityException {
        byte[] salt = randomBytes(SALT_LEN);
        byte[] iv = randomBytes(IV_LEN);
        SecretKey key = deriveKey(masterPassword, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
        buf.put(salt).put(iv).put(ciphertext);
        return PREFIX + Base64.getEncoder().encodeToString(buf.array());
    }

    static String decrypt(String wrapped, String masterPassword) throws GeneralSecurityException {
        if (!isWrapped(wrapped)) {
            throw new IllegalArgumentException("Value is not ENC1-wrapped");
        }
        byte[] blob = Base64.getDecoder().decode(wrapped.substring(PREFIX.length()).trim());
        if (blob.length < SALT_LEN + IV_LEN + 1) {
            throw new GeneralSecurityException("Encrypted blob too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        buf.get(salt).get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);
        SecretKey key = deriveKey(masterPassword, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(String masterPassword, byte[] salt) throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(masterPassword.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(skf.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
