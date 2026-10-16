package dev.nexo.erp.security;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSecurityService {

    private static final Set<String> AVATAR_COLORS = Set.of("emerald", "teal", "ocean", "forest", "gold", "slate");

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public AccountSecurityService(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public Profile profile(String username) {
        return jdbc.sql("""
                select u.id, u.username, u.display_name, u.email, u.phone, u.job_title, u.avatar_color, u.created_at,
                       u.password_changed_at, u.must_change_password,
                       u.mfa_required, u.mfa_enrolled_at,
                       coalesce(string_agg(r.name, ', '), '') as roles
                from app_user u
                left join user_role ur on ur.user_id = u.id
                left join role r on r.id = ur.role_id
                where lower(u.username) = lower(:username)
                group by u.id, u.username, u.display_name, u.email, u.phone, u.job_title, u.avatar_color, u.created_at,
                         u.password_changed_at, u.must_change_password,
                         u.mfa_required, u.mfa_enrolled_at
                """).param("username", username)
            .query((rs, row) -> new Profile(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("email"), rs.getString("phone"),
                rs.getString("job_title"), rs.getString("avatar_color"), rs.getString("roles"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("password_changed_at", OffsetDateTime.class),
                rs.getBoolean("must_change_password"), rs.getBoolean("mfa_required"),
                rs.getObject("mfa_enrolled_at", OffsetDateTime.class)))
            .single();
    }

    @Transactional
    public void updateProfile(String username, String displayName, String email, String phone,
                              String jobTitle, String avatarColor) {
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 160) {
            throw new IllegalArgumentException("Escribe un nombre válido");
        }
        if (email != null && !email.isBlank() &&
            (!email.strip().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.strip().length() > 254)) {
            throw new IllegalArgumentException("El correo no tiene un formato válido");
        }
        if (phone != null && !phone.isBlank() && !phone.strip().matches("[+0-9 ()-]{6,25}")) {
            throw new IllegalArgumentException("El teléfono no tiene un formato válido");
        }
        if (jobTitle != null && jobTitle.strip().length() > 120) {
            throw new IllegalArgumentException("El cargo es demasiado largo");
        }
        String safeColor = avatarColor == null ? "emerald" : avatarColor.strip();
        if (!AVATAR_COLORS.contains(safeColor)) {
            throw new IllegalArgumentException("El color de perfil no está permitido");
        }
        long userId = jdbc.sql("select id from app_user where lower(username) = lower(:username)")
            .param("username", username).query(Long.class).single();
        jdbc.sql("""
                update app_user set display_name = :displayName, email = :email, phone = :phone,
                    job_title = :jobTitle, avatar_color = :avatarColor, updated_at = current_timestamp
                where id = :id
                """)
            .param("displayName", displayName.strip()).param("email", nullable(email))
            .param("phone", nullable(phone)).param("jobTitle", nullable(jobTitle))
            .param("avatarColor", safeColor).param("id", userId).update();
        audit(userId, username, "PROFILE_UPDATE", "SUCCESS", null);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void changePassword(String username, String currentPassword, String newPassword,
                               String confirmation) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Escribe tu contraseña actual");
        }
        validatePassword(newPassword);
        if (!newPassword.equals(confirmation)) {
            throw new IllegalArgumentException("La confirmación no coincide con la nueva contraseña");
        }
        Account account = jdbc.sql("""
                select id, password_hash from app_user where lower(username) = lower(:username) for update
                """).param("username", username)
            .query((rs, row) -> new Account(rs.getLong("id"), rs.getString("password_hash"))).single();
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            audit(account.id(), username, "PASSWORD_CHANGE", "DENIED", "Contraseña actual incorrecta");
            throw new IllegalArgumentException("La contraseña actual no es correcta");
        }
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente de la actual");
        }
        jdbc.sql("""
                update app_user set password_hash = :hash, must_change_password = false, security_version=security_version+1,
                    password_changed_at = current_timestamp, failed_attempts = 0,
                    locked_until = null, updated_at = current_timestamp where id = :id
                """).param("hash", passwordEncoder.encode(newPassword)).param("id", account.id()).update();
        audit(account.id(), username, "PASSWORD_CHANGE", "SUCCESS", null);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
            || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
            || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException(
                "La contraseña debe tener 12 a 128 caracteres, mayúscula, minúscula y número");
        }
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void audit(long userId, String username, String action, String outcome, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:id, :action, 'USER', :username, :outcome, :correlationId, :details)
                """).param("id", userId).param("action", action).param("username", username)
            .param("outcome", outcome).param("correlationId", UUID.randomUUID().toString())
            .param("details", details).update();
    }

    private record Account(long id, String passwordHash) { }

    public record Profile(long id, String username, String displayName, String email, String phone,
                          String jobTitle, String avatarColor, String roles,
                          OffsetDateTime createdAt, OffsetDateTime passwordChangedAt,
                          boolean mustChangePassword, boolean mfaRequired,
                          OffsetDateTime mfaEnrolledAt) { }
}
