package ai.utkarsh.pop.infrastructure.security;

import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for registered database passwords.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a tampered ciphertext
 * fails to decrypt instead of yielding attacker-influenced plaintext. A fresh 12-byte IV is
 * generated per encryption and prepended to the ciphertext — reusing an IV under one key
 * catastrophically breaks GCM, so it is never derived from the data.
 *
 * <p>If no key is configured the cipher refuses to operate rather than falling back to storing
 * plaintext. That makes an unconfigured deployment fail loudly at registration time instead of
 * quietly accumulating readable secrets.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(SecurityProperties properties) {
        this.key = properties.hasSecretKey() ? parseKey(properties.secretKey()) : null;
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** @return base64 of {@code iv || ciphertext || tag} */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityRuntimeWrapper e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityRuntimeWrapper("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH) {
                throw new GeneralSecurityRuntimeWrapper("Stored secret is truncated", null);
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityRuntimeWrapper e) {
            throw e;
        } catch (Exception e) {
            // Includes AEADBadTagException — wrong key, or the row was tampered with.
            throw new GeneralSecurityRuntimeWrapper(
                    "Failed to decrypt a stored credential. The secret key may have changed "
                            + "since it was written.", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new SecretKeyNotConfiguredException();
        }
    }

    private static SecretKeySpec parseKey(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("pop.security.secret-key must be valid base64", e);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException(
                    "pop.security.secret-key must decode to 16, 24 or 32 bytes, got " + decoded.length);
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /** Unchecked so callers are not forced to handle a failure they cannot recover from. */
    public static class GeneralSecurityRuntimeWrapper extends RuntimeException {
        public GeneralSecurityRuntimeWrapper(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
