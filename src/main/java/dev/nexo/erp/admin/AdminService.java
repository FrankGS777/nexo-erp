package dev.nexo.erp.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.security.MfaService;

@Service
public class AdminService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final CatalogService catalog;
    private final MfaService mfa;

    public AdminService(JdbcClient jdbc, PasswordEncoder passwordEncoder, CatalogService catalog,
                        MfaService mfa) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.catalog = catalog;
        this.mfa = mfa;
    }

    public Company company() {
        return jdbc.sql("""
                select id, ruc, legal_name, trade_name, fiscal_address, ubigeo, website,
                       currency_code, timezone from company_profile order by id limit 1
                """)
            .query((rs, row) -> new Company(rs.getLong("id"), rs.getString("ruc"),
                rs.getString("legal_name"), rs.getString("trade_name"),
                rs.getString("fiscal_address"), rs.getString("ubigeo"), rs.getString("website"),
                rs.getString("currency_code"), rs.getString("timezone")))
            .single();
    }

    public FiscalConfiguration fiscal() {
        return jdbc.sql("""
                select environment, provider_code, endpoint_url, sol_username_reference,
                       sol_password_reference, certificate_reference,
                       certificate_password_reference, active, updated_at
                from fiscal_configuration order by id limit 1
                """)
            .query((rs, row) -> new FiscalConfiguration(rs.getString("environment"),
                rs.getString("provider_code"), rs.getString("endpoint_url"),
                rs.getString("sol_username_reference"), rs.getString("sol_password_reference"),
                rs.getString("certificate_reference"), rs.getString("certificate_password_reference"),
                rs.getBoolean("active"), rs.getObject("updated_at", OffsetDateTime.class)))
            .single();
    }

    public List<SeriesRow> series() {
        return jdbc.sql("""
                select ds.id, ds.document_type, ds.series, ds.next_number, ds.active, b.name as branch_name
                from document_series ds join branch b on b.id = ds.branch_id
                order by ds.document_type, ds.series
                """)
            .query((rs, row) -> new SeriesRow(rs.getLong("id"), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("next_number"), rs.getBoolean("active"),
                rs.getString("branch_name")))
            .list();
    }

    public List<UserRow> users() {
        return jdbc.sql("""
                select u.id, u.username, u.email, u.display_name, u.active, u.failed_attempts,
                       u.locked_until, u.created_at, u.must_change_password, u.mfa_required,
                       u.mfa_enrolled_at, u.mfa_locked_until,
                       coalesce(string_agg(r.code, ', '), '') as roles
                from app_user u
                left join user_role ur on ur.user_id = u.id
                left join role r on r.id = ur.role_id
                group by u.id, u.username, u.email, u.display_name, u.active,
                         u.failed_attempts, u.locked_until, u.created_at,
                         u.must_change_password, u.mfa_required, u.mfa_enrolled_at, u.mfa_locked_until
                order by u.active desc, u.display_name
                """)
            .query((rs, row) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                rs.getString("email"), rs.getString("display_name"), rs.getBoolean("active"),
                rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("roles"),
                rs.getBoolean("must_change_password"), rs.getBoolean("mfa_required"),
                rs.getObject("mfa_enrolled_at", OffsetDateTime.class),
                rs.getObject("mfa_locked_until", OffsetDateTime.class)))
            .list();
    }

    public List<RoleRow> roles() {
        return jdbc.sql("select id, code, name, description from role order by id")
            .query((rs, row) -> new RoleRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"))).list();
    }

    @Transactional
    public void updateCompany(String legalName, String tradeName, String address, String ubigeo,
                              String website, String username) {
        require(legalName, "La razón social es obligatoria");
        require(tradeName, "El nombre comercial es obligatorio");
        if (ubigeo != null && !ubigeo.isBlank() && !ubigeo.matches("\\d{6}")) {
            throw new IllegalArgumentException("El ubigeo debe tener 6 dígitos");
        }
        jdbc.sql("""
                update company_profile set legal_name = :legalName, trade_name = :tradeName,
                    fiscal_address = :address, ubigeo = :ubigeo, website = :website
                where id = (select min(id) from company_profile)
                """)
            .param("legalName", legalName.strip()).param("tradeName", tradeName.strip())
            .param("address", nullable(address)).param("ubigeo", nullable(ubigeo))
            .param("website", nullable(website)).update();
        audit(username, "COMPANY_UPDATE", "COMPANY", "1", "SUCCESS", "Perfil empresarial actualizado");
    }

    @Transactional
    public void updateFiscal(String environment, String providerCode, String endpointUrl,
                             String solReference, String solPasswordReference,
                             String certificateReference, String certificatePasswordReference,
                             boolean active,
                             String username) {
        if (!List.of("TEST", "PRODUCTION").contains(environment)) {
            throw new IllegalArgumentException("Entorno fiscal inválido");
        }
        if (!environment.equals(fiscal().environment())
            && jdbc.sql("select count(*) from electronic_document").query(Long.class).single() > 0) {
            throw new IllegalArgumentException("No se puede cambiar el entorno de una base con comprobantes. "
                + "Usa una base separada para produccion; los documentos de prueba no deben convertirse en reales.");
        }
        require(providerCode, "Selecciona el tipo de conexión fiscal");
        if (active && (isBlank(solReference) || isBlank(solPasswordReference)
            || isBlank(certificateReference) || isBlank(certificatePasswordReference)
            || "PENDING".equalsIgnoreCase(providerCode))) {
            throw new IllegalArgumentException("Para activar el envío indica las cuatro referencias seguras de usuario, claves y certificado");
        }
        validateSecretReference(solReference);
        validateSecretReference(solPasswordReference);
        validateSecretReference(certificateReference);
        validateSecretReference(certificatePasswordReference);
        jdbc.sql("""
                update fiscal_configuration set environment = :environment, provider_code = :provider,
                    endpoint_url = :endpoint, sol_username_reference = :solReference,
                    sol_password_reference = :solPasswordReference,
                    certificate_reference = :certificateReference,
                    certificate_password_reference = :certificatePasswordReference,
                    active = :active,
                    updated_at = current_timestamp
                where id = (select min(id) from fiscal_configuration)
                """)
            .param("environment", environment).param("provider", providerCode.strip())
            .param("endpoint", nullable(endpointUrl)).param("solReference", nullable(solReference))
            .param("solPasswordReference", nullable(solPasswordReference))
            .param("certificateReference", nullable(certificateReference))
            .param("certificatePasswordReference", nullable(certificatePasswordReference))
            .param("active", active).update();
        audit(username, "FISCAL_CONFIGURATION_UPDATE", "FISCAL_CONFIGURATION", "1", "SUCCESS",
            environment + ":" + providerCode);
    }

    @Transactional
    public void createUser(String username, String displayName, String email, String password,
                           long roleId, boolean mfaRequired, String actor) {
        require(username, "El usuario es obligatorio");
        require(displayName, "El nombre es obligatorio");
        validatePassword(password);
        if (mfaRequired && !mfa.configurationAvailable()) {
            throw new IllegalStateException("Configura NEXO_MFA_MASTER_KEY antes de crear usuarios con MFA obligatorio");
        }
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{4,40}")) {
            throw new IllegalArgumentException("El usuario debe tener 4 a 40 caracteres válidos");
        }
        jdbc.sql("""
                insert into app_user (username, email, display_name, password_hash, active,
                    must_change_password, mfa_required)
                values (:username, :email, :displayName, :passwordHash, true, true, :mfaRequired)
                """)
            .param("username", normalized).param("email", nullable(email))
            .param("displayName", displayName.strip()).param("passwordHash", passwordEncoder.encode(password))
            .param("mfaRequired", mfaRequired)
            .update();
        long userId = jdbc.sql("select id from app_user where username = :username")
            .param("username", normalized).query(Long.class).single();
        jdbc.sql("insert into user_role (user_id, role_id) values (:userId, :roleId)")
            .param("userId", userId).param("roleId", roleId).update();
        audit(actor, "USER_CREATE", "USER", String.valueOf(userId), "SUCCESS", normalized);
    }

    @Transactional
    public void toggleUser(long userId, String actor) {
        long actorId = catalog.userId(actor);
        if (userId == actorId) {
            throw new IllegalArgumentException("No puedes desactivar tu propia cuenta");
        }
        jdbc.sql("""
                update app_user set active = not active, failed_attempts = 0, security_version=security_version+1,
                    locked_until = null, updated_at = current_timestamp where id = :id
                """)
            .param("id", userId).update();
        audit(actor, "USER_TOGGLE", "USER", String.valueOf(userId), "SUCCESS", null);
    }

    @Transactional
    public void resetPassword(long userId, String newPassword, String actor) {
        validatePassword(newPassword);
        jdbc.sql("""
                update app_user set password_hash = :hash, failed_attempts = 0, security_version=security_version+1,
                    locked_until = null, must_change_password = true,
                    updated_at = current_timestamp where id = :id
                """)
            .param("hash", passwordEncoder.encode(newPassword)).param("id", userId).update();
        audit(actor, "USER_PASSWORD_RESET", "USER", String.valueOf(userId), "SUCCESS", null);
    }

    @Transactional
    public void changeRole(long userId, long roleId, String actor) {
        jdbc.sql("update app_user set security_version=security_version+1 where id=:id").param("id",userId).update();
        jdbc.sql("delete from user_role where user_id = :userId").param("userId", userId).update();
        jdbc.sql("insert into user_role (user_id, role_id) values (:userId, :roleId)")
            .param("userId", userId).param("roleId", roleId).update();
        audit(actor, "USER_ROLE_UPDATE", "USER", String.valueOf(userId), "SUCCESS",
            "role=" + roleId);
    }

    @Transactional
    public void updateSeries(long id, long nextNumber, boolean active, String username) {
        if (nextNumber <= 0) {
            throw new IllegalArgumentException("El siguiente correlativo debe ser positivo");
        }
        long current = jdbc.sql("select next_number from document_series where id = :id for update")
            .param("id", id).query(Long.class).single();
        if (nextNumber < current) {
            throw new IllegalArgumentException("No se puede retroceder un correlativo fiscal");
        }
        jdbc.sql("""
                update document_series set next_number = :nextNumber, active = :active,
                    version = version + 1 where id = :id
                """)
            .param("nextNumber", nextNumber).param("active", active).param("id", id).update();
        audit(username, "DOCUMENT_SERIES_UPDATE", "DOCUMENT_SERIES", String.valueOf(id),
            "SUCCESS", String.valueOf(nextNumber));
    }

    public void setMfaRequired(long userId, boolean required, String actor) {
        mfa.setRequired(userId, required, actor);
    }

    public void resetMfa(long userId, String actor) {
        mfa.reset(userId, actor);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
            || !password.matches(".*[A-Z].*")
            || !password.matches(".*[a-z].*") || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 12 caracteres, mayúscula, minúscula y número");
        }
    }

    private void audit(String username, String action, String type, String id, String outcome, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:actor, :action, :type, :id, :outcome, :correlationId, :details)
                """)
            .param("actor", catalog.userId(username)).param("action", action).param("type", type)
            .param("id", id).param("outcome", outcome).param("correlationId", UUID.randomUUID().toString())
            .param("details", details).update();
    }

    private void require(String value, String message) {
        if (isBlank(value)) throw new IllegalArgumentException(message);
    }

    private void validateSecretReference(String reference) {
        if (isBlank(reference)) return;
        if (!(reference.startsWith("env://") || reference.startsWith("base64env://")
            || reference.startsWith("file://") || reference.startsWith("aws-secrets://"))) {
            throw new IllegalArgumentException("Las credenciales deben ser referencias seguras, nunca valores directos");
        }
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String nullable(String value) { return isBlank(value) ? null : value.strip(); }

    public record Company(long id, String ruc, String legalName, String tradeName, String address,
                          String ubigeo, String website, String currency, String timezone) { }
    public record FiscalConfiguration(String environment, String providerCode, String endpointUrl,
                                      String solReference, String solPasswordReference,
                                      String certificateReference, String certificatePasswordReference,
                                      boolean active,
                                      OffsetDateTime updatedAt) { }
    public record SeriesRow(long id, String documentType, String series, long nextNumber,
                            boolean active, String branchName) { }
    public record UserRow(long id, String username, String email, String displayName, boolean active,
                          int failedAttempts, OffsetDateTime lockedUntil, OffsetDateTime createdAt,
                          String roles, boolean mustChangePassword, boolean mfaRequired,
                          OffsetDateTime mfaEnrolledAt, OffsetDateTime mfaLockedUntil) { }
    public record RoleRow(long id, String code, String name, String description) { }
}
