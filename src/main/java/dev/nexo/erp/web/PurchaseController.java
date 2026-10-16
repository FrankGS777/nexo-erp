package dev.nexo.erp.web;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.purchases.PurchaseService;

@Controller
public class PurchaseController {

    private final PurchaseService purchases;
    private final CatalogService catalog;

    public PurchaseController(PurchaseService purchases, CatalogService catalog) {
        this.purchases = purchases;
        this.catalog = catalog;
    }

    @GetMapping("/modulos/compras")
    String list(Principal principal, Model model) {
        model.addAttribute("purchases", purchases.purchases());
        model.addAttribute("currentUser", principal.getName());
        return "purchases";
    }

    @GetMapping("/modulos/comprobantes/recibidos")
    String receivedDocuments(Principal principal, Model model) {
        model.addAttribute("purchases", purchases.purchases());
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("sunatInboxConnected", false);
        return "received-documents";
    }

    @GetMapping("/modulos/compras/nueva")
    String form(Principal principal, Model model) {
        model.addAttribute("suppliers", catalog.suppliers("", true));
        model.addAttribute("products", catalog.products("", false));
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("currentUser", principal.getName());
        return "purchase-form";
    }

    @GetMapping("/modulos/compras/{id}")
    String detail(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("purchase", purchases.detail(id));
        model.addAttribute("currentUser", principal.getName());
        return "purchase-detail";
    }

    @PostMapping("/modulos/compras")
    String create(
        @RequestParam long supplierId,
        @RequestParam String documentType,
        @RequestParam(required = false) String series,
        @RequestParam String number,
        @RequestParam LocalDate issueDate,
        @RequestParam(name = "productId") List<Long> productIds,
        @RequestParam(name = "quantity") List<BigDecimal> quantities,
        @RequestParam(name = "unitCost") List<BigDecimal> unitCosts,
        @RequestParam(name = "lotNumber", required = false) List<String> lots,
        @RequestParam(name = "expirationDate", required = false) List<LocalDate> expirations,
        @RequestParam(required = false) String notes,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            long purchaseId = purchases.create(supplierId, documentType, series, number, issueDate, productIds, quantities,
                unitCosts, lots, expirations, notes, principal.getName());
            redirect.addFlashAttribute("success", "Compra registrada. El ingreso físico quedó pendiente para almacén");
            return "redirect:/modulos/compras/" + purchaseId;
        } catch (DataIntegrityViolationException exception) {
            redirect.addFlashAttribute("error", "El comprobante del proveedor ya fue registrado");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/compras/nueva";
    }

    @PostMapping("/modulos/compras/{id}/recibir")
    String receive(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        try {
            purchases.receive(id, principal.getName());
            redirect.addFlashAttribute("success", "Mercadería recibida y existencias actualizadas");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/compras/" + id;
    }

    @PostMapping("/modulos/compras/{id}/anular")
    String cancel(@PathVariable long id, @RequestParam String reason, Principal principal,
                  RedirectAttributes redirect) {
        try {
            purchases.cancel(id, reason, principal.getName());
            redirect.addFlashAttribute("success", "Compra anulada y stock revertido de forma trazable");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", exception instanceof IllegalArgumentException
                ? exception.getMessage() : "No fue posible anular la compra");
        }
        return "redirect:/modulos/compras/" + id;
    }
}
