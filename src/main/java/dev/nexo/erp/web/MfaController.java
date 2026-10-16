package dev.nexo.erp.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.security.MfaService;
import dev.nexo.erp.security.MfaSession;
import dev.nexo.erp.security.MfaQrCode;

@Controller
public class MfaController {

    private final MfaService mfa;
    private final MfaQrCode qr;

    public MfaController(MfaService mfa, MfaQrCode qr) {
        this.mfa = mfa;
        this.qr = qr;
    }

    @GetMapping("/seguridad/mfa")
    String mfa(Principal principal, HttpSession session, Model model) {
        MfaService.MfaStatus status = mfa.status(principal.getName());
        if (status.enrolled() && Boolean.TRUE.equals(session.getAttribute(MfaSession.VERIFIED))) {
            return "redirect:/mi-cuenta";
        }
        model.addAttribute("username", principal.getName());
        model.addAttribute("enrolled", status.enrolled());
        model.addAttribute("lockedUntil", status.lockedUntil());
        model.addAttribute("configurationError", false);
        if (!status.enrolled()) {
            if (!mfa.configurationAvailable()) {
                model.addAttribute("configurationError", true);
                return "mfa";
            }
            String secret = (String) session.getAttribute(MfaSession.PENDING_SECRET);
            if (secret == null) {
                secret = mfa.newSecret();
                session.setAttribute(MfaSession.PENDING_SECRET, secret);
            }
            model.addAttribute("secret", mfa.groupedSecret(secret));
            String uri = mfa.provisioningUri(principal.getName(), secret);
            model.addAttribute("provisioningUri", uri);
            model.addAttribute("mfaQr", qr.dataUri(uri));
        }
        return "mfa";
    }

    @PostMapping("/seguridad/mfa/verificar")
    String verify(@RequestParam String code, Principal principal, HttpServletRequest request,
                  RedirectAttributes redirect) {
        HttpSession session = request.getSession(false);
        try {
            MfaService.MfaStatus status = mfa.status(principal.getName());
            if (!status.enrolled()) {
                String secret = session == null ? null
                    : (String) session.getAttribute(MfaSession.PENDING_SECRET);
                if (secret == null) {
                    throw new IllegalStateException("La configuración MFA venció; vuelve a comenzar");
                }
                MfaService.Enrollment enrollment = mfa.enroll(principal.getName(), secret, code,
                    request.getRemoteAddr());
                request.changeSessionId();
                session = request.getSession(false);
                session.setAttribute(MfaSession.VERIFIED, true);
                session.removeAttribute(MfaSession.PENDING_SECRET);
                session.setAttribute(MfaSession.RECOVERY_CODES, enrollment.recoveryCodes());
                return "redirect:/seguridad/mfa/codigos-recuperacion";
            }
            MfaService.VerificationResult result = mfa.verify(principal.getName(), code,
                request.getRemoteAddr());
            if (!result.valid()) {
                redirect.addFlashAttribute("error", result.message());
                return "redirect:/seguridad/mfa";
            }
            request.changeSessionId();
            request.getSession(false).setAttribute(MfaSession.VERIFIED, true);
            if (result.usedRecoveryCode()) {
                redirect.addFlashAttribute("warning",
                    "Ingresaste con un código de recuperación; ese código ya no volverá a funcionar");
            }
            return mfa.status(principal.getName()).mustChangePassword()
                ? "redirect:/mi-cuenta?changeRequired" : "redirect:/";
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/seguridad/mfa";
        }
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/seguridad/mfa/codigos-recuperacion")
    String recoveryCodes(Principal principal, HttpSession session, Model model) {
        List<String> codes = (List<String>) session.getAttribute(MfaSession.RECOVERY_CODES);
        if (codes == null || codes.isEmpty()) {
            return "redirect:/mi-cuenta";
        }
        session.removeAttribute(MfaSession.RECOVERY_CODES);
        model.addAttribute("username", principal.getName());
        model.addAttribute("codes", codes);
        model.addAttribute("codesText", String.join("\n", codes));
        return "mfa-recovery";
    }
}
