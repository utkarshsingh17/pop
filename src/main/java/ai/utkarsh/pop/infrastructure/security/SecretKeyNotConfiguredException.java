package ai.utkarsh.pop.infrastructure.security;

/** Raised when a secret must be protected but {@code pop.security.secret-key} is unset. */
public class SecretKeyNotConfiguredException extends RuntimeException {

    public SecretKeyNotConfiguredException() {
        super("pop.security.secret-key is not configured, so database credentials cannot be "
                + "encrypted at rest. Set it (base64-encoded 32 bytes) and retry.");
    }
}
