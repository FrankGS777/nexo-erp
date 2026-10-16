package dev.nexo.erp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            MfaAuthenticationSuccessHandler successHandler,
                                            MfaEnforcementFilter mfaEnforcementFilter) throws Exception {
        return http
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/fonts/**", "/icons/**", "/favicon.svg").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/seguridad/mfa/**", "/mi-cuenta/**").authenticated()
                .requestMatchers("/").hasAuthority("DASHBOARD_VIEW")
                .requestMatchers("/modulos/contexto/**").hasAnyAuthority("CUSTOMER_MANAGE","PURCHASE_MANAGE","QUOTE_MANAGE")
                .requestMatchers("/modulos/comercial/gestion/**").hasAuthority("PRICE_APPROVE")
                .requestMatchers("/modulos/comercial/**").hasAuthority("QUOTE_MANAGE")
                .requestMatchers(HttpMethod.POST,"/modulos/ventas/*/pagos").hasAuthority("PAYMENT_VERIFY")
                .requestMatchers(HttpMethod.POST, "/modulos/comprobantes/**").hasAuthority("DOCUMENT_ISSUE")
                .requestMatchers("/modulos/comprobantes/**").hasAuthority("DOCUMENT_VIEW")
                .requestMatchers("/modulos/ventas/**").hasAuthority("SALES_MANAGE")
                .requestMatchers("/modulos/inventario/**").hasAuthority("INVENTORY_MANAGE")
                .requestMatchers("/modulos/clientes/**").hasAuthority("CUSTOMER_MANAGE")
                .requestMatchers("/modulos/compras/**").hasAuthority("PURCHASE_MANAGE")
                .requestMatchers("/modulos/proveedores/**").hasAuthority("PURCHASE_MANAGE")
                .requestMatchers("/modulos/reportes/**").hasAuthority("REPORT_VIEW")
                .requestMatchers("/modulos/configuracion/**").hasAuthority("SECURITY_ADMIN")
                .requestMatchers("/acceso-denegado", "/error").authenticated()
                .anyRequest().denyAll())
            .formLogin(login -> login
                .loginPage("/login")
                .successHandler(successHandler)
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .exceptionHandling(exceptions -> exceptions.accessDeniedPage("/acceso-denegado"))
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.migrateSession())
                .maximumSessions(2))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; style-src 'self'; " +
                    "script-src 'self'; font-src 'self'; connect-src 'self'; " +
                    "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(permissions -> permissions
                    .policy("camera=(), microphone=(), geolocation=(), payment=()")))
            .addFilterAfter(mfaEnforcementFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

}
