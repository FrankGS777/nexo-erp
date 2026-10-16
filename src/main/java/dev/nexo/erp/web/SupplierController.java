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
public class SupplierController {

    private final CatalogService catalog;

    public SupplierController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/modulos/proveedores")
    String list(@RequestParam(defaultValue = "") String q, Principal principal, Model model) {
        model.addAttribute("suppliers", catalog.suppliers(q, false));
        model.addAttribute("query", q);
        model.addAttribute("currentUser", principal.getName());
        return "suppliers";
    }

    @GetMapping("/modulos/proveedores/nuevo")
    String newForm(Principal principal, Model model) {
        model.addAttribute("editing", false);
        model.addAttribute("currentUser", principal.getName());
        return "supplier-form";
    }

    @GetMapping("/modulos/proveedores/{id}/editar")
    String editForm(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("supplier", catalog.supplier(id));
        model.addAttribute("editing", true);
        model.addAttribute("currentUser", principal.getName());
        return "supplier-form";
    }

    @PostMapping("/modulos/proveedores")
    String create(@RequestParam String documentType, @RequestParam String documentNumber,
                  @RequestParam String legalName, @RequestParam(required = false) String tradeName,
                  @RequestParam(required = false) String contactName, @RequestParam(required = false) String email,
                  @RequestParam(required = false) String phone, @RequestParam(required = false) String address,
                  @RequestParam(required = false) String ubigeo, @RequestParam(defaultValue = "PE") String countryCode,
                  Principal principal, RedirectAttributes redirect) {
        try {
            catalog.createSupplier(documentType, documentNumber, legalName, tradeName, contactName, email, phone,
                address, ubigeo, countryCode, principal.getName());
            redirect.addFlashAttribute("success", "Proveedor registrado correctamente");
        } catch (DataIntegrityViolationException exception) {
            redirect.addFlashAttribute("error", "Ya existe un proveedor con ese documento");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/proveedores";
    }

    @PostMapping("/modulos/proveedores/{id}")
    String update(@PathVariable long id, @RequestParam String legalName,
                  @RequestParam(required = false) String tradeName,
                  @RequestParam(required = false) String contactName, @RequestParam(required = false) String email,
                  @RequestParam(required = false) String phone, @RequestParam(required = false) String address,
                  @RequestParam(required = false) String ubigeo, @RequestParam(defaultValue = "PE") String countryCode,
                  Principal principal, RedirectAttributes redirect) {
        try {
            catalog.updateSupplier(id, legalName, tradeName, contactName, email, phone, address, ubigeo,
                countryCode, principal.getName());
            redirect.addFlashAttribute("success", "Proveedor actualizado");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/proveedores";
    }

    @PostMapping("/modulos/proveedores/{id}/estado")
    String toggle(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        catalog.toggle("supplier", id, principal.getName());
        redirect.addFlashAttribute("success", "Estado del proveedor actualizado");
        return "redirect:/modulos/proveedores";
    }
}
