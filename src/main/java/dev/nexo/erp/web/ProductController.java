package dev.nexo.erp.web;

import java.math.BigDecimal;
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
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/modulos/inventario/productos")
    String products(@RequestParam(defaultValue = "") String q, Principal principal, Model model) {
        model.addAttribute("products", catalog.products(q, false));
        model.addAttribute("query", q);
        model.addAttribute("currentUser", principal.getName());
        return "products";
    }

    @GetMapping("/modulos/inventario/productos/nuevo")
    String newForm(Principal principal, Model model) {
        model.addAttribute("images", catalog.productImages());
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("editing", false);
        return "product-form";
    }

    @GetMapping("/modulos/inventario/productos/{id}/editar")
    String editForm(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("product", catalog.product(id));
        model.addAttribute("images", catalog.productImages());
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("editing", true);
        return "product-form";
    }

    @PostMapping("/modulos/inventario/productos")
    String create(
        @RequestParam String sku,
        @RequestParam String name,
        @RequestParam(required = false) String commercialDescription,
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "NEXO DEMO") String brand,
        @RequestParam(required = false) String presentation,
        @RequestParam(required = false) String imagePath,
        @RequestParam(required = false) String sunatProductCode,
        @RequestParam(required = false) String barcode,
        @RequestParam(defaultValue = "NIU") String unitCode,
        @RequestParam(defaultValue = "10") String igvAffectationCode,
        @RequestParam(defaultValue = "18") BigDecimal taxRate,
        @RequestParam BigDecimal salePrice,
        @RequestParam(defaultValue = "0") BigDecimal purchasePrice,
        @RequestParam(defaultValue = "0") BigDecimal minimumStock,
        @RequestParam(defaultValue = "false") boolean stockControlled,
        @RequestParam(defaultValue = "0") BigDecimal initialStock,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            catalog.createProduct(sku, name, commercialDescription, category, brand, presentation, imagePath,
                sunatProductCode, barcode, unitCode, igvAffectationCode, taxRate, salePrice, purchasePrice,
                minimumStock, stockControlled, initialStock, principal.getName());
            redirect.addFlashAttribute("success", "Producto registrado correctamente");
        } catch (DataIntegrityViolationException exception) {
            redirect.addFlashAttribute("error", "Ya existe un producto con ese código");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/inventario/productos";
    }

    @PostMapping("/modulos/inventario/productos/{id}")
    String update(
        @PathVariable long id,
        @RequestParam String name,
        @RequestParam(required = false) String commercialDescription,
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "NEXO DEMO") String brand,
        @RequestParam(required = false) String presentation,
        @RequestParam(required = false) String imagePath,
        @RequestParam(required = false) String sunatProductCode,
        @RequestParam(required = false) String barcode,
        @RequestParam String unitCode,
        @RequestParam(defaultValue = "10") String igvAffectationCode,
        @RequestParam(defaultValue = "18") BigDecimal taxRate,
        @RequestParam BigDecimal salePrice,
        @RequestParam(defaultValue = "0") BigDecimal purchasePrice,
        @RequestParam(defaultValue = "0") BigDecimal minimumStock,
        @RequestParam(defaultValue = "false") boolean stockControlled,
        Principal principal,
        RedirectAttributes redirect
    ) {
        try {
            catalog.updateProduct(id, name, commercialDescription, category, brand, presentation, imagePath,
                sunatProductCode, barcode, unitCode, igvAffectationCode, taxRate, salePrice, purchasePrice,
                minimumStock, stockControlled, principal.getName());
            redirect.addFlashAttribute("success", "Producto actualizado");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/modulos/inventario/productos";
    }

    @PostMapping("/modulos/inventario/productos/{id}/estado")
    String toggle(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        catalog.toggle("product", id, principal.getName());
        redirect.addFlashAttribute("success", "Estado del producto actualizado");
        return "redirect:/modulos/inventario/productos";
    }
}
