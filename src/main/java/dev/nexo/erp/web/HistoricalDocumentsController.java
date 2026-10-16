package dev.nexo.erp.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import dev.nexo.erp.documents.HistoricalDocument;
import dev.nexo.erp.documents.HistoricalDocumentCatalog;

@Controller
public class HistoricalDocumentsController {

    private static final int PAGE_SIZE = 25;
    private final HistoricalDocumentCatalog catalog;

    public HistoricalDocumentsController(HistoricalDocumentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/modulos/comprobantes")
    public String list(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "") String type,
        @RequestParam(defaultValue = "") String state,
        @RequestParam(defaultValue = "0") int page,
        Principal principal,
        Model model
    ) {
        var all = catalog.documents();
        var filtered = catalog.find(q, type, state);
        int safePage = Math.max(0, page);
        int from = Math.min(safePage * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());

        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("documents", filtered.subList(from, to));
        model.addAttribute("query", q.strip());
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedState", state);
        model.addAttribute("page", safePage);
        model.addAttribute("hasPrevious", safePage > 0);
        model.addAttribute("hasNext", to < filtered.size());
        model.addAttribute("filteredCount", filtered.size());
        model.addAttribute("totalCount", all.size());
        model.addAttribute("readableCount", all.stream()
            .filter(item -> item.validationState() == HistoricalDocument.ValidationState.READABLE).count());
        model.addAttribute("incidentCount", all.stream()
            .filter(item -> item.validationState() != HistoricalDocument.ValidationState.READABLE).count());
        return "documents";
    }
}
