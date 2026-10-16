package dev.nexo.erp.security;

public final class MfaSession {
    public static final String VERIFIED = "NEXO_MFA_VERIFIED";
    public static final String PENDING_SECRET = "NEXO_MFA_PENDING_SECRET";
    public static final String RECOVERY_CODES = "NEXO_MFA_RECOVERY_CODES";

    private MfaSession() { }
}
