package dev.nexo.erp.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@Configuration
@ConditionalOnProperty(name = "app.security.persistent-sessions", havingValue = "true")
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
public class PersistentSessionConfig {
}
