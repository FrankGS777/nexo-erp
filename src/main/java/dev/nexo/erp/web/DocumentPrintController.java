package dev.nexo.erp.web;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ResponseStatus;
import dev.nexo.erp.billing.PrintDocumentService;

@Controller
public class DocumentPrintController {
    private static final Logger log=LoggerFactory.getLogger(DocumentPrintController.class);
    private final PrintDocumentService print;
    public DocumentPrintController(PrintDocumentService print){this.print=print;}
    @GetMapping("/modulos/comprobantes/emitidos/{id}/imprimir")
    String fiscal(@PathVariable long id,Model m){m.addAttribute("doc",print.fiscal(id));return "document-customer-print";}
    @GetMapping("/modulos/comercial/{id}/despachos/{dispatch}/imprimir")
    String dispatch(@PathVariable long id,@PathVariable long dispatch,Principal p,Model m){m.addAttribute("doc",print.dispatch(id,dispatch,p.getName()));return "document-print";}
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String printFailure(RuntimeException error,Model model) {
        String reference=UUID.randomUUID().toString().substring(0,8).toUpperCase();
        log.error("Print view failed [{}]",reference,error);
        model.addAttribute("reference",reference);
        return "print-error";
    }
}
