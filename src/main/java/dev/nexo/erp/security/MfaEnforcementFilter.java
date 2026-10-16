package dev.nexo.erp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class MfaEnforcementFilter extends OncePerRequestFilter {

    private final MfaService mfa;
    private final SessionAccessService sessions;

    public MfaEnforcementFilter(MfaService mfa, SessionAccessService sessions) {
        this.mfa = mfa;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!sessions.validate(authentication.getName(),request.getSession(true))) {
            new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
                .logout(request,response,authentication);
            response.sendRedirect(request.getContextPath()+"/login?expired");
            return;
        }
        if (allowedPath(request)) {
            filterChain.doFilter(request,response);
            return;
        }
        MfaService.MfaStatus status = mfa.status(authentication.getName());
        boolean verified = Boolean.TRUE.equals(request.getSession(false) == null ? null
            : request.getSession(false).getAttribute(MfaSession.VERIFIED));
        if (status.required() && !verified) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.sendRedirect(request.getContextPath() + "/seguridad/mfa");
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Completa la verificación MFA");
            }
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (status.mustChangePassword() && !path.startsWith("/mi-cuenta")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.sendRedirect(request.getContextPath() + "/mi-cuenta?changeRequired");
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Debes cambiar la contraseña temporal");
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowedPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/login") || path.equals("/logout") || path.equals("/error")
            || path.startsWith("/seguridad/mfa") || path.startsWith("/css/")
            || path.startsWith("/fonts/") || path.startsWith("/icons/") || path.startsWith("/js/") || path.startsWith("/images/")
            || path.equals("/favicon.svg") || path.equals("/actuator/health");
    }
}
