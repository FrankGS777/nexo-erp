package dev.nexo.erp.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.security.AccountSecurityService;
import dev.nexo.erp.security.MfaService;
import dev.nexo.erp.security.MfaQrCode;
import dev.nexo.erp.security.MfaSession;

@Controller
public class AccountController {

    private final AccountSecurityService accounts;
    private final MfaService mfa;
    private final MfaQrCode qr;

    public AccountController(AccountSecurityService accounts, MfaService mfa, MfaQrCode qr) {
        this.accounts = accounts;
        this.mfa = mfa;
        this.qr = qr;
    }

    @PostMapping("/mi-cuenta/autenticador")
    String authenticator(@RequestParam String currentPassword, Principal principal,
                         HttpServletRequest request, HttpServletResponse response,
                         Model model, RedirectAttributes redirect) {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Referrer-Policy", "no-referrer");
        var session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(MfaSession.VERIFIED))) {
            return "redirect:/seguridad/mfa";
        }
        try {
            String uri = mfa.confirmedProvisioningUri(principal.getName(), currentPassword, request.getRemoteAddr());
            model.addAttribute("mfaQr", qr.dataUri(uri));
            model.addAttribute("username", principal.getName());
            return "mfa-link";
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/mi-cuenta";
        }
    }

    @GetMapping("/mi-cuenta")
    String account(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("profile", accounts.profile(principal.getName()));
        model.addAttribute("mfa", mfa.status(principal.getName()));
        return "account";
    }

    @PostMapping("/mi-cuenta/clave")
    String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                          @RequestParam String confirmation, Authentication authentication,
                          HttpServletRequest request, HttpServletResponse response,
                          RedirectAttributes redirect) {
        try {
            accounts.changePassword(authentication.getName(), currentPassword, newPassword, confirmation);
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            return "redirect:/login?passwordChanged";
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/mi-cuenta";
        }
    }

    @PostMapping("/mi-cuenta/perfil")
    String updateProfile(@RequestParam String displayName, @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String jobTitle,
                         @RequestParam(defaultValue = "emerald") String avatarColor,
                         Principal principal, RedirectAttributes redirect) {
        try {
            accounts.updateProfile(principal.getName(), displayName, email, phone, jobTitle, avatarColor);
            redirect.addFlashAttribute("success", "Perfil actualizado");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/mi-cuenta";
    }
}
