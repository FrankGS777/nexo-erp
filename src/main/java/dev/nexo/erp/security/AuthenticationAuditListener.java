package dev.nexo.erp.security;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationAuditListener {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private final JdbcClient jdbc;

    public AuthenticationAuditListener(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String username = normalize(event.getAuthentication().getName());
        OffsetDateTime lockUntil = OffsetDateTime.now().plusMinutes(15);
        jdbc.sql("""
                update app_user
                set failed_attempts = failed_attempts + 1,
                    locked_until = case
                        when failed_attempts + 1 >= :maxAttempts then :lockUntil
                        else locked_until
                    end,
                    updated_at = current_timestamp
                where lower(username) = :username
                """)
            .param("maxAttempts", MAX_FAILED_ATTEMPTS)
            .param("lockUntil", lockUntil)
            .param("username", username)
            .update();
        audit(username, "LOGIN_FAILURE", "DENIED", remoteAddress(event.getAuthentication().getDetails()));
    }

    @EventListener
    public void onLocked(AuthenticationFailureLockedEvent event) {
        String username = normalize(event.getAuthentication().getName());
        audit(username, "LOGIN_BLOCKED", "DENIED", remoteAddress(event.getAuthentication().getDetails()));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = normalize(event.getAuthentication().getName());
        jdbc.sql("""
                update app_user
                set failed_attempts = 0, locked_until = null, updated_at = current_timestamp
                where lower(username) = :username
                """)
            .param("username", username)
            .update();
        audit(username, "PASSWORD_SUCCESS", "SUCCESS", remoteAddress(event.getAuthentication().getDetails()));
    }

    private void audit(String username, String action, String outcome, String sourceIp) {
        jdbc.sql("""
                insert into audit_event (
                    actor_user_id, action, resource_type, resource_id, outcome, source_ip, correlation_id
                )
                select id, :action, 'USER', username, :outcome, :sourceIp, :correlationId
                from app_user
                where lower(username) = :username
                """)
            .param("action", action)
            .param("outcome", outcome)
            .param("sourceIp", sourceIp)
            .param("correlationId", UUID.randomUUID().toString())
            .param("username", username)
            .update();
    }

    private String remoteAddress(Object details) {
        return details instanceof WebAuthenticationDetails webDetails
            ? webDetails.getRemoteAddress()
            : null;
    }

    private String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
