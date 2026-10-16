package dev.nexo.erp.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.admin.AdminService;

@Controller
public class ConfigurationController {

    private final AdminService admin;
    private final org.springframework.core.env.Environment environment;

    public ConfigurationController(AdminService admin, org.springframework.core.env.Environment environment) {
        this.admin = admin;
        this.environment = environment;
    }

    @GetMapping("/modulos/configuracion")
    public String settings(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("company", admin.company());
        model.addAttribute("fiscal", admin.fiscal());
        model.addAttribute("series", admin.series());
        model.addAttribute("users", admin.users());
        model.addAttribute("roles", admin.roles());
        model.addAttribute("localFiscalSetup", environment.matchesProfiles("local"));
        return "settings";
    }

    @PostMapping("/modulos/configuracion/empresa")
    public String company(@RequestParam String legalName, @RequestParam String tradeName,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String ubigeo,
                          @RequestParam(required = false) String website,
                          Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.updateCompany(legalName, tradeName, address, ubigeo, website,
            principal.getName()), "Datos de empresa actualizados", redirect);
    }

    @PostMapping("/modulos/configuracion/fiscal")
    public String fiscal(@RequestParam String environment, @RequestParam String providerCode,
                         @RequestParam(required = false) String endpointUrl,
                         @RequestParam(required = false) String solReference,
                         @RequestParam(required = false) String solPasswordReference,
                         @RequestParam(required = false) String certificateReference,
                         @RequestParam(required = false) String certificatePasswordReference,
                         @RequestParam(defaultValue = "false") boolean active,
                         Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.updateFiscal(environment, providerCode, endpointUrl, solReference,
            solPasswordReference, certificateReference, certificatePasswordReference,
            active, principal.getName()), "Configuración fiscal actualizada", redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios")
    public String user(@RequestParam String username, @RequestParam String displayName,
                       @RequestParam(required = false) String email, @RequestParam String password,
                       @RequestParam long roleId,
                       @RequestParam(defaultValue = "false") boolean mfaRequired,
                       Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.createUser(username, displayName, email, password, roleId, mfaRequired,
            principal.getName()), "Usuario creado correctamente", redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios/{id}/estado")
    public String toggleUser(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.toggleUser(id, principal.getName()), "Estado del usuario actualizado", redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios/{id}/clave")
    public String resetPassword(@PathVariable long id, @RequestParam String password,
                                Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.resetPassword(id, password, principal.getName()),
            "Contraseña reemplazada y bloqueo reiniciado", redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios/{id}/rol")
    public String changeRole(@PathVariable long id, @RequestParam long roleId,
                             Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.changeRole(id, roleId, principal.getName()),
            "Rol del usuario actualizado", redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios/{id}/mfa")
    public String mfaRequirement(@PathVariable long id,
                                 @RequestParam(defaultValue = "false") boolean required,
                                 Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.setMfaRequired(id, required, principal.getName()),
            required ? "MFA será obligatorio para el usuario" : "MFA dejó de ser obligatorio",
            redirect);
    }

    @PostMapping("/modulos/configuracion/usuarios/{id}/mfa/restablecer")
    public String resetMfa(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.resetMfa(id, principal.getName()),
            "MFA restablecido; el usuario deberá vincular nuevamente su autenticador", redirect);
    }

    @PostMapping("/modulos/configuracion/series/{id}")
    public String series(@PathVariable long id, @RequestParam long nextNumber,
                         @RequestParam(defaultValue = "false") boolean active,
                         Principal principal, RedirectAttributes redirect) {
        return run(() -> admin.updateSeries(id, nextNumber, active, principal.getName()),
            "Serie actualizada", redirect);
    }

    private String run(Runnable action, String success, RedirectAttributes redirect) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", exception instanceof IllegalArgumentException
                ? exception.getMessage() : "No fue posible guardar. Verifica datos duplicados o inválidos.");
        }
        return "redirect:/modulos/configuracion";
    }
}
