package dev.nexo.erp.web;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.sales.SaleService;

@Controller
public class SalesController {

    private final SaleService sales;
    private final CatalogService catalog;

    public SalesController(SaleService sales, CatalogService catalog) {
        this.sales = sales;
        this.catalog = catalog;
    }

    @GetMapping("/modulos/ventas")
    public String list(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("sales", sales.sales());
        return "sales";
    }

    @GetMapping("/modulos/ventas/nueva")
    public String form(Principal principal, Model model) {
        return "redirect:/modulos/comercial/nueva";
    }

    @PostMapping("/modulos/ventas")
    public String create(@RequestParam long customerId,
                         @RequestParam String documentType,
                         @RequestParam LocalDate issueDate,
                         @RequestParam List<Long> productId,
                         @RequestParam List<BigDecimal> quantity,
                         @RequestParam(defaultValue = "PEN") String currency,
                         @RequestParam(defaultValue = "1") BigDecimal exchangeRate,
                         @RequestParam(defaultValue = "0101") String operationType,
                         @RequestParam(defaultValue = "CONTADO") String paymentCondition,
                         @RequestParam(required = false) LocalDate dueDate,
                         @RequestParam(required = false) String purchaseOrderReference,
                         @RequestParam(required = false) String relatedGuideReference,
                         @RequestParam(required = false) String deliveryAddress,
                         @RequestParam(required = false) String deliveryUbigeo,
                         @RequestParam(required = false) String detractionCode,
                         @RequestParam(required = false) BigDecimal detractionPercent,
                         @RequestParam(required = false) String paymentMethod,
                         @RequestParam(required = false) String notes,
                         Principal principal,
                         RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("warning", "Registra el pedido y factura sus cantidades desde Despacho.");
            return "redirect:/modulos/comercial/nueva";
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
            return "redirect:/modulos/ventas/nueva";
        }
    }

    @GetMapping("/modulos/ventas/{id}")
    public String detail(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("sale", sales.detail(id));
        model.addAttribute("paymentRequestKey",java.util.UUID.randomUUID().toString());
        return "sale-detail";
    }

    @GetMapping("/modulos/ventas/{id}/imprimir")
    public String print(@PathVariable long id, Principal principal, Model model) {
        return "redirect:/modulos/comprobantes/emitidos/"+sales.detail(id).header().electronicDocumentId()+"/imprimir";
    }

    @PostMapping("/modulos/ventas/{id}/pagos")
    public String payment(@PathVariable long id,
                          @RequestParam BigDecimal amount,
                          @RequestParam String method,
                          @RequestParam(required = false) String reference,
                          @RequestParam String requestKey,
                          Principal principal,
                          RedirectAttributes redirect) {
        try {
            sales.registerPayment(id, amount, method, reference, requestKey, principal.getName());
            redirect.addFlashAttribute("success", "Pago registrado correctamente");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/ventas/" + id;
    }

    @PostMapping("/modulos/ventas/{id}/notas")
    public String adjustment(@PathVariable long id,
                             @RequestParam String documentType,
                             @RequestParam String reasonCode,
                             @RequestParam String reason,
                             @RequestParam BigDecimal amount,
                             Principal principal,
                             RedirectAttributes redirect) {
        try {
            sales.createAdjustment(id, documentType, reasonCode, reason, amount, principal.getName());
            redirect.addFlashAttribute("success", "Nota registrada y lista para generar su XML");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/ventas/" + id;
    }

    private String friendly(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        return "No fue posible completar la operación. Verifica que los datos no estén duplicados.";
    }
}
