package ai.utkarsh.pop.infrastructure.security;

import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static SecretCipher cipherWith(String key) {
        return new SecretCipher(new SecurityProperties(key, List.of()));
    }

    @Test
    void shouldRoundTripASecret() {
        SecretCipher cipher = cipherWith(KEY);

        assertThat(cipher.decrypt(cipher.encrypt("hunter2"))).isEqualTo("hunter2");
    }

    @Test
    void shouldProduceADifferentCiphertextEachTime() {
        SecretCipher cipher = cipherWith(KEY);

        // A fixed IV would leak that two services share a password; GCM would also be broken
        // outright by IV reuse under one key.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void shouldRefuseTamperedCiphertext() {
        SecretCipher cipher = cipherWith(KEY);
        String encrypted = cipher.encrypt("hunter2");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(SecretCipher.GeneralSecurityRuntimeWrapper.class);
    }

    @Test
    void shouldRefuseToDecryptUnderADifferentKey() {
        String other = Base64.getEncoder()
                .encodeToString("fedcba9876543210fedcba9876543210".getBytes());
        String encrypted = cipherWith(KEY).encrypt("hunter2");

        assertThatThrownBy(() -> cipherWith(other).decrypt(encrypted))
                .isInstanceOf(SecretCipher.GeneralSecurityRuntimeWrapper.class);
    }

    @Test
    void shouldFailClosedWhenNoKeyIsConfigured() {
        SecretCipher cipher = cipherWith(null);

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("hunter2"))
                .isInstanceOf(SecretKeyNotConfiguredException.class);
    }

    @Test
    void shouldRejectAKeyOfTheWrongLength() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> cipherWith(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16, 24 or 32 bytes");
    }
}
