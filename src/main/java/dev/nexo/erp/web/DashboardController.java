package dev.nexo.erp.web;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import dev.nexo.erp.documents.HistoricalDocumentInventory;
import dev.nexo.erp.documents.HistoricalDocumentInventoryService;
import dev.nexo.erp.reports.ReportService;

@Controller
public class DashboardController {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern(
        "EEEE, d 'de' MMMM", Locale.forLanguageTag("es-PE")
    );

    private final HistoricalDocumentInventoryService inventoryService;
    private final ReportService reports;
    private final JdbcClient jdbc;
    private final dev.nexo.erp.commercial.CommercialService commercial;

    public DashboardController(HistoricalDocumentInventoryService inventoryService, ReportService reports,
                               JdbcClient jdbc,dev.nexo.erp.commercial.CommercialService commercial) {
        this.inventoryService = inventoryService;
        this.reports = reports;
        this.jdbc = jdbc;
        this.commercial = commercial;
    }

    @GetMapping("/")
    String dashboard(Model model, Principal principal) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("QUOTE_MANAGE")) &&
            auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("PORTFOLIO_ALL")))
            return "redirect:/modulos/comercial";
        HistoricalDocumentInventory inventory = inventoryService.scan();
        model.addAttribute("inventory", inventory);
        model.addAttribute("today", capitalize(DATE_FORMAT.format(LocalDate.now())));
        model.addAttribute("currentUser", principal.getName());
        boolean canSeeOrders=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("QUOTE_MANAGE"));
        model.addAttribute("canSeeOrders",canSeeOrders);
        model.addAttribute("recentOrders",canSeeOrders?commercial.orders(principal.getName(),"","").stream()
            .filter(o->!java.util.List.of("CANCELLED","CLOSED","COMPLETED").contains(o.get("status"))).limit(6).toList():java.util.List.of());
        model.addAttribute("orderStates",java.util.Map.of("DRAFT","Borrador","SENT","Cotizado","WAITING_STOCK","Por abastecer","CONFIRMED","Confirmado","PARTIAL","Parcial","PREPARED","Por despachar"));
        model.addAttribute("summary", reports.summary());
        model.addAttribute("customerCount", count("customer"));
        model.addAttribute("productCount", count("product"));
        model.addAttribute("pendingDocuments", jdbc.sql("select count(*) from electronic_document where status <> 'ACCEPTED'")
            .query(Long.class).single());
        model.addAttribute("fiscalActive", jdbc.sql("select active from fiscal_configuration order by id limit 1")
            .query(Boolean.class).single());
        return "dashboard";
    }

    private long count(String table) {
        if (!table.equals("customer") && !table.equals("product")) throw new IllegalArgumentException();
        return jdbc.sql("select count(*) from " + table + " where active = true").query(Long.class).single();
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
