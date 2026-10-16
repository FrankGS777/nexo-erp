package dev.nexo.erp.web;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.catalog.CatalogService;

@Controller
public class InventoryController {

    private final CatalogService catalog;

    public InventoryController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/modulos/inventario")
    String inventory(Principal principal, Model model) {
        var products = catalog.products("", false);
        model.addAttribute("products", products);
        model.addAttribute("movements", catalog.movements());
        model.addAttribute("lowStockCount", products.stream()
            .filter(item -> item.stockControlled() && item.stock().compareTo(BigDecimal.valueOf(5)) <= 0).count());
        model.addAttribute("currentUser", principal.getName());
        return "inventory";
    }

    @GetMapping("/modulos/inventario/existencias")
    String stock(@RequestParam(defaultValue = "") String q, Principal principal, Model model) {
        var products = catalog.products(q, false);
        model.addAttribute("products", products);
        model.addAttribute("query", q);
        model.addAttribute("lowStockCount", products.stream()
            .filter(item -> item.stockControlled() && item.stock().compareTo(item.minimumStock()) <= 0).count());
        model.addAttribute("currentUser", principal.getName());
        return "inventory-stock";
    }

    @GetMapping("/modulos/inventario/kardex")
    String kardex(Principal principal, Model model) {
        model.addAttribute("movements", catalog.movements());
        model.addAttribute("currentUser", principal.getName());
        return "inventory-kardex";
    }

    @GetMapping("/modulos/inventario/lotes")
    String lots(Principal principal, Model model) {
        var movements = catalog.movements();
        model.addAttribute("lots", movements.stream()
            .filter(item -> item.lotNumber() != null && !item.lotNumber().isBlank()).toList());
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("today", LocalDate.now());
        return "inventory-lots";
    }

    @GetMapping("/modulos/inventario/ajustes")
    String adjustmentForm(Principal principal, Model model) {
        model.addAttribute("products", catalog.products("", false));
        model.addAttribute("currentUser", principal.getName());
        return "inventory-adjustment";
    }

    @PostMapping("/modulos/inventario/ajustes")
    String adjust(
        @RequestParam long productId,
        @RequestParam BigDecimal quantity,
        @RequestParam String direction,
        @RequestParam(required = false) String lotNumber,
        @RequestParam(required = false) LocalDate expirationDate,
        @RequestParam(required = false) String notes,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            catalog.adjustStock(productId, quantity, direction, lotNumber, expirationDate, notes, principal.getName());
            redirect.addFlashAttribute("success", "Movimiento de inventario registrado");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/inventario/kardex";
    }
}
