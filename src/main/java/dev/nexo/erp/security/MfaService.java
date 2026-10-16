package dev.nexo.erp.security;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {

    private static final int MAX_FAILURES = 5;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final JdbcClient jdbc;
    private final TotpService totp;
    private final MfaCryptoService crypto;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();
    private final boolean forceMfa;

    public MfaService(JdbcClient jdbc, TotpService totp, MfaCryptoService crypto,
                      PasswordEncoder passwordEncoder, @Value("${NEXO_REQUIRE_MFA:false}") boolean forceMfa) {
        this.jdbc = jdbc;
        this.totp = totp;
        this.crypto = crypto;
        this.passwordEncoder = passwordEncoder;
        this.forceMfa = forceMfa;
        if (forceMfa && !crypto.available()) throw new IllegalStateException("MFA obligatorio requiere NEXO_MFA_MASTER_KEY");
    }

    public boolean configurationAvailable() {
        return crypto.available();
    }

    public MfaStatus status(String username) {
        return jdbc.sql("""
                select id, mfa_required, mfa_secret_encrypted is not null as enrolled,
                       mfa_enrolled_at, mfa_locked_until,
                       must_change_password,
                       (select count(*) from mfa_recovery_code rc
                        where rc.user_id = u.id and rc.used_at is null) as recovery_codes
                from app_user u where lower(username) = :username
                """)
            .param("username", normalize(username))
            .query((rs, row) -> new MfaStatus(rs.getLong("id"), forceMfa || rs.getBoolean("mfa_required"),
                rs.getBoolean("enrolled"), rs.getObject("mfa_enrolled_at", OffsetDateTime.class),
                rs.getObject("mfa_locked_until", OffsetDateTime.class),
                rs.getBoolean("must_change_password"), rs.getInt("recovery_codes")))
            .optional().orElseThrow(() -> new IllegalArgumentException("La cuenta no existe"));
    }

    public String newSecret() {
        if (!configurationAvailable()) {
            throw new IllegalStateException(
                "El administrador debe configurar NEXO_MFA_MASTER_KEY antes de activar MFA");
        }
        return totp.generateSecret();
    }

    public String provisioningUri(String username, String secret) {
        return totp.provisioningUri("Nexo ERP", username, secret);
    }

    public String groupedSecret(String secret) {
        return totp.grouped(secret);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public String confirmedProvisioningUri(String username, String currentPassword, String sourceIp) {
        MfaAccount account = accountForUpdate(username);
        if (account.encryptedSecret() == null) throw new IllegalArgumentException("Activa primero el autenticador");
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Espera a que termine el bloqueo temporal de seguridad");
        }
        String hash = jdbc.sql("select password_hash from app_user where id=:id and active=true and must_change_password=false")
            .param("id", account.id()).query(String.class).optional().orElse(null);
        if (hash == null || currentPassword == null || currentPassword.length() > 128
            || !passwordEncoder.matches(currentPassword, hash)) {
            fail(username, account.id(), sourceIp, "Confirmacion de clave incorrecta al solicitar QR MFA");
            throw new IllegalArgumentException("No se pudo confirmar tu contrasena actual");
        }
        audit(username, "MFA_PROVISIONING_VIEW", "SUCCESS", sourceIp,
            "QR solicitado tras confirmar contrasena; secreto no incluido en auditoria");
        return provisioningUri(username, crypto.decrypt(account.encryptedSecret()));
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Enrollment enroll(String username, String secret, String code, String sourceIp) {
        long userId = userId(username);
        TotpService.Verification verification = totp.verify(secret, code);
        if (!verification.valid()) {
            audit(username, "MFA_ENROLLMENT_FAILURE", "DENIED", sourceIp,
                "Código TOTP inválido durante la vinculación");
            throw new IllegalArgumentException("El código no coincide. Revisa la hora del teléfono e inténtalo otra vez");
        }
        String encrypted = crypto.encrypt(secret);
        jdbc.sql("""
                update app_user set mfa_required = true, mfa_secret_encrypted = :secret,
                    mfa_enrolled_at = current_timestamp, mfa_last_used_step = :step,
                    mfa_failed_attempts = 0, mfa_locked_until = null,
                    updated_at = current_timestamp where id = :id
                """)
            .param("secret", encrypted).param("step", verification.step())
            .param("id", userId).update();
        jdbc.sql("delete from mfa_recovery_code where user_id = :id").param("id", userId).update();
        List<String> recoveryCodes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String recoveryCode = recoveryCode();
            recoveryCodes.add(recoveryCode);
            jdbc.sql("insert into mfa_recovery_code (user_id, code_hash) values (:id, :hash)")
                .param("id", userId).param("hash", passwordEncoder.encode(normalizeRecovery(recoveryCode)))
                .update();
        }
        audit(username, "MFA_ENROLLMENT_SUCCESS", "SUCCESS", sourceIp,
            "Segundo factor activado; códigos de recuperación emitidos");
        return new Enrollment(List.copyOf(recoveryCodes));
    }

    @Transactional
    public VerificationResult verify(String username, String submittedCode, String sourceIp) {
        MfaAccount account = accountForUpdate(username);
        if (!account.required()) {
            audit(username, "LOGIN_SUCCESS", "SUCCESS", sourceIp, "MFA no requerido");
            return VerificationResult.success(false);
        }
        if (account.encryptedSecret() == null) {
            throw new IllegalStateException("La cuenta debe completar la configuración MFA");
        }
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(OffsetDateTime.now())) {
            audit(username, "MFA_FAILURE", "DENIED", sourceIp, "Segundo factor temporalmente bloqueado");
            return VerificationResult.failure("MFA está bloqueado temporalmente por intentos fallidos");
        }

        String secret = crypto.decrypt(account.encryptedSecret());
        TotpService.Verification totpResult = totp.verify(secret, submittedCode);
        if (totpResult.valid()) {
            int changed = jdbc.sql("""
                    update app_user set mfa_last_used_step = :step, mfa_failed_attempts = 0,
                        mfa_locked_until = null, updated_at = current_timestamp
                    where id = :id and (mfa_last_used_step is null or mfa_last_used_step < :step)
                    """)
                .param("step", totpResult.step()).param("id", account.id()).update();
            if (changed == 1) {
                audit(username, "LOGIN_SUCCESS", "SUCCESS", sourceIp, "MFA TOTP verificado");
                return VerificationResult.success(false);
            }
            fail(username, account.id(), sourceIp, "Código TOTP reutilizado");
            return VerificationResult.failure("Ese código ya fue utilizado; espera el siguiente código");
        }

        Long recoveryId = matchingRecoveryCode(account.id(), submittedCode);
        if (recoveryId != null) {
            int changed = jdbc.sql("""
                    update mfa_recovery_code set used_at = current_timestamp
                    where id = :id and used_at is null
                    """).param("id", recoveryId).update();
            if (changed == 1) {
                jdbc.sql("""
                        update app_user set mfa_failed_attempts = 0, mfa_locked_until = null,
                            updated_at = current_timestamp where id = :id
                        """).param("id", account.id()).update();
                audit(username, "LOGIN_SUCCESS", "SUCCESS", sourceIp, "Código de recuperación MFA consumido");
                return VerificationResult.success(true);
            }
        }

        fail(username, account.id(), sourceIp, "Código MFA inválido");
        return VerificationResult.failure("Código incorrecto. También puedes usar un código de recuperación");
    }

    @Transactional
    public void setRequired(long userId, boolean required, String actor) {
        if (forceMfa && !required) throw new IllegalArgumentException("La politica del servidor exige MFA para todos los usuarios");
        if (required && !configurationAvailable()) {
            throw new IllegalStateException("Configura NEXO_MFA_MASTER_KEY antes de exigir MFA");
        }
        int changed = jdbc.sql("""
                update app_user set mfa_required = :required, security_version=security_version+1, updated_at = current_timestamp
                where id = :id
                """).param("required", required).param("id", userId).update();
        if (changed != 1) throw new IllegalArgumentException("El usuario no existe");
        audit(actor, "MFA_REQUIREMENT_UPDATE", "SUCCESS", null,
            "user=" + userId + ", required=" + required);
    }

    @Transactional
    public void reset(long userId, String actor) {
        int changed = jdbc.sql("""
                update app_user set mfa_secret_encrypted = null, mfa_enrolled_at = null, security_version=security_version+1,
                    mfa_last_used_step = null, mfa_failed_attempts = 0, mfa_locked_until = null,
                    updated_at = current_timestamp where id = :id
                """).param("id", userId).update();
        if (changed != 1) throw new IllegalArgumentException("El usuario no existe");
        jdbc.sql("delete from mfa_recovery_code where user_id = :id").param("id", userId).update();
        audit(actor, "MFA_RESET", "SUCCESS", null, "user=" + userId);
    }

    public void recordPasswordOnlyLogin(String username, String sourceIp) {
        audit(username, "LOGIN_SUCCESS", "SUCCESS", sourceIp, "MFA no requerido");
    }

    private void fail(String username, long userId, String sourceIp, String details) {
        OffsetDateTime lockUntil = OffsetDateTime.now().plusMinutes(15);
        jdbc.sql("""
                update app_user set mfa_failed_attempts = mfa_failed_attempts + 1,
                    mfa_locked_until = case when mfa_failed_attempts + 1 >= :maximum
                        then :lockUntil else mfa_locked_until end,
                    updated_at = current_timestamp where id = :id
                """).param("maximum", MAX_FAILURES).param("lockUntil", lockUntil)
            .param("id", userId).update();
        audit(username, "MFA_FAILURE", "DENIED", sourceIp, details);
    }

    private Long matchingRecoveryCode(long userId, String submittedCode) {
        String normalized = normalizeRecovery(submittedCode);
        if (!normalized.matches("[A-Z2-9]{10}")) return null;
        List<RecoveryHash> codes = jdbc.sql("""
                select id, code_hash from mfa_recovery_code
                where user_id = :id and used_at is null order by id
                """).param("id", userId)
            .query((rs, row) -> new RecoveryHash(rs.getLong("id"), rs.getString("code_hash"))).list();
        return codes.stream().filter(code -> passwordEncoder.matches(normalized, code.hash()))
            .map(RecoveryHash::id).findFirst().orElse(null);
    }

    private MfaAccount accountForUpdate(String username) {
        return jdbc.sql("""
                select id, mfa_required, mfa_secret_encrypted, mfa_locked_until
                from app_user where lower(username) = :username for update
                """).param("username", normalize(username))
            .query((rs, row) -> new MfaAccount(rs.getLong("id"), forceMfa || rs.getBoolean("mfa_required"),
                rs.getString("mfa_secret_encrypted"), rs.getObject("mfa_locked_until", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new IllegalArgumentException("La cuenta no existe"));
    }

    private long userId(String username) {
        return jdbc.sql("select id from app_user where lower(username) = :username")
            .param("username", normalize(username)).query(Long.class).single();
    }

    private String recoveryCode() {
        StringBuilder value = new StringBuilder(12);
        for (int index = 0; index < 10; index++) {
            if (index == 4 || index == 8) value.append('-');
            value.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return value.toString();
    }

    private void audit(String username, String action, String outcome, String sourceIp, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, source_ip, correlation_id, details)
                select id, :action, 'USER', username, :outcome, :sourceIp, :correlationId, :details
                from app_user where lower(username) = :username
                """).param("action", action).param("outcome", outcome).param("sourceIp", sourceIp)
            .param("correlationId", UUID.randomUUID().toString()).param("details", details)
            .param("username", normalize(username)).update();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private String normalizeRecovery(String value) {
        return value == null ? "" : value.replace("-", "").replace(" ", "")
            .toUpperCase(Locale.ROOT);
    }

    private record MfaAccount(long id, boolean required, String encryptedSecret,
                              OffsetDateTime lockedUntil) { }
    private record RecoveryHash(long id, String hash) { }

    public record MfaStatus(long userId, boolean required, boolean enrolled,
                            OffsetDateTime enrolledAt, OffsetDateTime lockedUntil,
                            boolean mustChangePassword,
                            int remainingRecoveryCodes) { }
    public record Enrollment(List<String> recoveryCodes) { }
    public record VerificationResult(boolean valid, boolean usedRecoveryCode, String message) {
        static VerificationResult success(boolean recovery) {
            return new VerificationResult(true, recovery, null);
        }
        static VerificationResult failure(String message) {
            return new VerificationResult(false, false, message);
        }
    }
}
