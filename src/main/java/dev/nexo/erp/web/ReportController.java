package dev.nexo.erp.web;

import java.security.Principal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import dev.nexo.erp.reports.ReportService;

@Controller
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/modulos/reportes")
    public String report(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("summary", reports.summary());
        model.addAttribute("monthly", reports.monthlySales());
        model.addAttribute("customers", reports.topCustomers());
        model.addAttribute("stockAlerts", reports.stockAlerts());
        model.addAttribute("audit", reports.recentAudit());
        model.addAttribute("monthStart", LocalDate.now().withDayOfMonth(1));
        model.addAttribute("today", LocalDate.now());
        return "reports";
    }

    @GetMapping("/modulos/reportes/ventas.csv")
    public ResponseEntity<byte[]> salesCsv(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        byte[] content = reports.salesCsv(from, to);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("ventas-nexo.csv").build().toString())
            .body(content);
    }
}
