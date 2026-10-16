package dev.nexo.erp.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ErrorPageController {
    @GetMapping("/acceso-denegado")
    public String denied(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        return "access-denied";
    }
}
