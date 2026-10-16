package dev.nexo.erp.web;

import java.security.Principal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.catalog.CatalogService;

@Controller
public class CustomerController {

    private final CatalogService catalog;

    public CustomerController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/modulos/clientes")
    String list(@RequestParam(defaultValue = "") String q, Principal principal, Model model) {
        var customers = catalog.customers(q);
        model.addAttribute("customers", customers);
        model.addAttribute("query", q);
        model.addAttribute("activeCount", customers.stream().filter(CatalogService.CustomerRow::active).count());
        model.addAttribute("currentUser", principal.getName());
        return "customers";
    }

    @GetMapping("/modulos/clientes/nuevo")
    String newForm(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("editing", false);
        return "customer-form";
    }

    @GetMapping("/modulos/clientes/{id}/editar")
    String editForm(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("customer", catalog.customer(id));
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("editing", true);
        return "customer-form";
    }

    @PostMapping("/modulos/clientes")
    String create(
        @RequestParam String documentType,
        @RequestParam String documentNumber,
        @RequestParam String legalName,
        @RequestParam(required = false) String tradeName,
        @RequestParam(required = false) String contactName,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String address,
        @RequestParam(required = false) String ubigeo,
        @RequestParam(defaultValue = "PE") String countryCode,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            catalog.createCustomer(documentType, documentNumber, legalName, tradeName, contactName, email, phone,
                address, ubigeo, countryCode, principal.getName());
            redirect.addFlashAttribute("success", "Cliente registrado correctamente");
        } catch (DataIntegrityViolationException exception) {
            redirect.addFlashAttribute("error", "Ya existe un cliente con ese documento");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/clientes";
    }

    @PostMapping("/modulos/clientes/{id}")
    String update(
        @PathVariable long id,
        @RequestParam String legalName,
        @RequestParam(required = false) String tradeName,
        @RequestParam(required = false) String contactName,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String address,
        @RequestParam(required = false) String ubigeo,
        @RequestParam(defaultValue = "PE") String countryCode,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            catalog.updateCustomer(id, legalName, tradeName, contactName, email, phone, address, ubigeo,
                countryCode, principal.getName());
            redirect.addFlashAttribute("success", "Datos del cliente actualizados");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/clientes";
    }

    @PostMapping("/modulos/clientes/{id}/estado")
    String toggle(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        catalog.toggle("customer", id, principal.getName());
        redirect.addFlashAttribute("success", "Estado del cliente actualizado");
        return "redirect:/modulos/clientes";
    }
}
