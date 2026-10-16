package dev.nexo.erp.web;

import java.security.Principal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.billing.ElectronicDocumentService;

@Controller
public class ElectronicDocumentController {

    private final ElectronicDocumentService documents;
    private final dev.nexo.erp.admin.AdminService admin;

    public ElectronicDocumentController(ElectronicDocumentService documents,dev.nexo.erp.admin.AdminService admin) {
        this.documents = documents;
        this.admin=admin;
    }

    @GetMapping("/modulos/comprobantes/emitidos")
    public String list(Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("issuedDocuments", documents.issuedDocuments());
        return "issued-documents";
    }

    @GetMapping("/modulos/comprobantes/emitidos/{id}")
    public String detail(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("currentUser", principal.getName());
        model.addAttribute("document", documents.issuedDocument(id));
        model.addAttribute("events", documents.documentEvents(id));
        model.addAttribute("fiscalActions", documents.fiscalActionsForDocument(id));
        model.addAttribute("fiscalMode",admin.fiscal().environment());
        model.addAttribute("cdrObservations", documents.cdrObservations(id));
        model.addAttribute("profileBlocked", documents.isProfileBlocked(id));
        return "issued-document-detail";
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/generar")
    public String generate(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        try {
            var result = documents.generate(id, principal.getName());
            redirect.addFlashAttribute("success", "XML UBL generado. SHA-256: " + result.sha256().substring(0, 12));
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos/" + id;
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/emitir")
    public String emit(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        try {
            var result=documents.emit(id,principal.getName());
            submissionFeedback(result, redirect);
        }catch(RuntimeException e){redirect.addFlashAttribute("error",friendly(e));}
        return "redirect:/modulos/comprobantes/emitidos/"+id;
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/enviar")
    public String submit(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        try {
            var result = documents.submit(id, principal.getName());
            submissionFeedback(result, redirect);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos/" + id;
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/cdr")
    public String cdr(@PathVariable long id, @RequestParam("file") MultipartFile file,
                      Principal principal, RedirectAttributes redirect) {
        try {
            var result = documents.registerCdr(id, file, principal.getName());
            redirect.addFlashAttribute("success", "CDR preservado. Estado fiscal: " + result.status());
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos/" + id;
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/anular")
    public String cancel(@PathVariable long id, @RequestParam String reason,
                         Principal principal, RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("success", documents.requestCancellation(id, reason, principal.getName()));
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos/" + id;
    }

    @PostMapping("/modulos/comprobantes/emitidos/{id}/conciliar")
    public String reconcile(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        try {
            var result = documents.reconcile(id, principal.getName());
            submissionFeedback(result, redirect);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos/" + id;
    }

    @PostMapping("/modulos/comprobantes/acciones/{id}/enviar")
    public String submitCancellation(@PathVariable long id, Principal principal,
                                     RedirectAttributes redirect) {
        try {
            var result = documents.submitCancellation(id, principal.getName());
            cancellationFeedback(result, redirect);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos";
    }

    @PostMapping("/modulos/comprobantes/acciones/{id}/consultar")
    public String pollCancellation(@PathVariable long id, Principal principal,
                                   RedirectAttributes redirect) {
        try {
            var result = documents.pollCancellation(id, principal.getName());
            cancellationFeedback(result, redirect);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", friendly(exception));
        }
        return "redirect:/modulos/comprobantes/emitidos";
    }

    @GetMapping("/modulos/comprobantes/emitidos/{id}/xml")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable long id) {
        var resource = documents.xmlResource(id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(documents.downloadName(id)).build().toString())
            .body(resource);
    }

    @GetMapping("/modulos/comprobantes/emitidos/{id}/cdr")
    public ResponseEntity<org.springframework.core.io.Resource> downloadCdr(@PathVariable long id) {
        var resource = documents.cdrResource(id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(documents.cdrDownloadName(id)).build().toString())
            .body(resource);
    }

    private void submissionFeedback(dev.nexo.erp.billing.FiscalGateway.SubmissionResult result,
                                    RedirectAttributes redirect) {
        switch (result.status()) {
            case ACCEPTED -> redirect.addFlashAttribute("success", "Comprobante aceptado por SUNAT.");
            case OBSERVED -> redirect.addFlashAttribute("success", "Comprobante aceptado con observaciones. Revisa el CDR.");
            case REJECTED, NOT_CONFIGURED -> redirect.addFlashAttribute("error", result.message());
            default -> redirect.addFlashAttribute("error", "Envío pendiente de confirmación. Consulta el estado en SUNAT antes de reintentar.");
        }
    }

    private void cancellationFeedback(dev.nexo.erp.billing.FiscalGateway.SubmissionResult result,
                                      RedirectAttributes redirect) {
        switch (result.status()) {
            case ACCEPTED -> redirect.addFlashAttribute("success", "Baja aceptada por SUNAT.");
            case OBSERVED -> redirect.addFlashAttribute("success", "Baja aceptada con observaciones. Revisa el CDR.");
            default -> redirect.addFlashAttribute("error", result.message());
        }
    }

    private String friendly(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            return exception.getMessage();
        }
        return "No fue posible procesar el comprobante electrónico";
    }
}
