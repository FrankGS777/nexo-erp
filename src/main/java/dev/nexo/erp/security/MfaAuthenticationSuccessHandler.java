package dev.nexo.erp.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class MfaAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final MfaService mfa;
    private final SessionAccessService sessions;

    public MfaAuthenticationSuccessHandler(MfaService mfa, SessionAccessService sessions) {
        this.mfa = mfa;
        this.sessions = sessions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        MfaService.MfaStatus status = mfa.status(authentication.getName());
        sessions.validate(authentication.getName(), request.getSession(true));
        boolean verified = !status.required();
        request.getSession(true).setAttribute(MfaSession.VERIFIED, verified);
        if (verified) {
            mfa.recordPasswordOnlyLogin(authentication.getName(), request.getRemoteAddr());
            response.sendRedirect(request.getContextPath()
                + (status.mustChangePassword() ? "/mi-cuenta?changeRequired" : "/"));
        } else {
            response.sendRedirect(request.getContextPath() + "/seguridad/mfa");
        }
    }
}
