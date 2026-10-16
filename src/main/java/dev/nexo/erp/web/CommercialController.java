package dev.nexo.erp.web;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import dev.nexo.erp.commercial.CommercialService;
import dev.nexo.erp.commercial.CommercialAccess;

@Controller
@RequestMapping("/modulos/comercial")
public class CommercialController {
    private final CommercialService service;
    private final CommercialAccess access;
    private final dev.nexo.erp.billing.PrintDocumentService printService;
    public CommercialController(CommercialService service,CommercialAccess access,dev.nexo.erp.billing.PrintDocumentService printService) {this.service=service;this.access=access;this.printService=printService;}
    @ModelAttribute
    void common(Model model,Principal principal) {
        model.addAttribute("currentUser",principal.getName());
        model.addAttribute("canApprove",access.has(principal.getName(),"PRICE_APPROVE"));
        model.addAttribute("canDispatch",access.has(principal.getName(),"DISPATCH_MANAGE"));
        model.addAttribute("statusLabels",Map.of("DRAFT","Borrador","SENT","Cotizado","WAITING_STOCK","Por abastecer",
            "CONFIRMED","Confirmado","PARTIAL","Parcial","PREPARED","Por despachar","COMPLETED","Completado","CLOSED","Cerrado","CANCELLED","Cancelado"));
        model.addAttribute("today",LocalDate.now());
    }
    @GetMapping
    String list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String estado,Principal p,Model m) {
        var orders=service.orders(p.getName(),q,estado);m.addAttribute("orders",orders);m.addAttribute("query",q);m.addAttribute("selectedStatus",estado);
        m.addAttribute("draftCount",orders.stream().filter(o->List.of("DRAFT","SENT").contains(o.get("status"))).count());
        m.addAttribute("pendingCount",orders.stream().filter(o->List.of("CONFIRMED","PARTIAL","PREPARED","WAITING_STOCK").contains(o.get("status"))).count());
        return "commercial-orders";
    }
    @GetMapping("/nueva")
    String form(Principal p,Model m) {m.addAttribute("customers",service.customers(p.getName()));return "commercial-new";}
    @GetMapping("/clientes/buscar") @ResponseBody
    List<CommercialService.CustomerOption> searchCustomers(@RequestParam(defaultValue="") String q,Principal p) {
        return service.findCustomers(p.getName(),q);
    }
    @PostMapping
    String create(@RequestParam long customerId,@RequestParam(required=false) LocalDate requestedDate,
        @RequestParam(defaultValue="") String address,@RequestParam(defaultValue="CONTADO") String condition,
        @RequestParam(defaultValue="") String notes,Principal p) {
        return "redirect:/modulos/comercial/"+service.create(customerId,requestedDate,address,condition,notes,p.getName());
    }
    @GetMapping("/{id}")
    String detail(@PathVariable long id,@RequestParam(defaultValue="") String q,Principal p,Model m) {
        var o=service.order(id,p.getName());m.addAttribute("order",o);m.addAttribute("lines",service.lines(id,p.getName()));
        m.addAttribute("products",service.prices(((Number)o.get("customer_id")).longValue(),p.getName(),q));
        m.addAttribute("dispatches",service.dispatches(id,p.getName()));m.addAttribute("activity",service.activity(id,p.getName()));
        if(access.has(p.getName(),"DISPATCH_MANAGE")) {
            m.addAttribute("companyAddress",service.companyAddress(p.getName()));
            m.addAttribute("transportProfiles",service.transportProfiles(p.getName()));
        }
        m.addAttribute("query",q);m.addAttribute("requestKey",UUID.randomUUID().toString());return "commercial-detail";
    }
    @GetMapping("/{id}/imprimir")
    String print(@PathVariable long id,Principal p,Model m) {
        m.addAttribute("doc",printService.quote(id,p.getName()));return "document-print";
    }
    @PostMapping("/{id}/lineas")
    String line(@PathVariable long id,@RequestParam long productId,@RequestParam BigDecimal quantity,
        @RequestParam(required=false) BigDecimal netPrice,Principal p) {
        service.line(id,productId,quantity,netPrice,p.getName());return back(id);
    }
    @PostMapping("/{id}/quitar")
    String remove(@PathVariable long id,@RequestParam long productId,Principal p) {service.removeLine(id,productId,p.getName());return back(id);}
    @PostMapping("/{id}/estado")
    String transition(@PathVariable long id,@RequestParam String action,@RequestParam(defaultValue="") String reason,
        @RequestParam(required=false) LocalDate promisedDate,Principal p,RedirectAttributes r) {
        service.transition(id,action,reason,promisedDate,p.getName());r.addFlashAttribute("success","Operacion registrada");return back(id);
    }
    @PostMapping("/{id}/despachos")
    String prepare(@PathVariable long id,@RequestParam List<Long> lineId,@RequestParam List<BigDecimal> quantity,
        @RequestParam String requestKey,Principal p,RedirectAttributes r) {
        service.prepare(id,lineId,quantity,requestKey,p.getName());r.addFlashAttribute("success","Factura preparada. Completa su emision y la guia antes de confirmar la salida.");return back(id);
    }
    @PostMapping("/{id}/despachos/{dispatch}/guia")
    String guide(@PathVariable long id,@PathVariable long dispatch,@RequestParam String guide,
        @RequestParam(defaultValue="") String carrier,@RequestParam(defaultValue="") String plate,
        @RequestParam LocalDate date,Principal p) {service.externalGuide(id,dispatch,guide,carrier,plate,date,p.getName());return back(id);}
    @PostMapping("/{id}/despachos/{dispatch}/salida")
    String depart(@PathVariable long id,@PathVariable long dispatch,Principal p) {service.depart(id,dispatch,p.getName());return back(id);}
    @PostMapping("/{id}/despachos/{dispatch}/transporte")
    String transport(@PathVariable long id,@PathVariable long dispatch,@RequestParam Map<String,String> fields,Principal p,RedirectAttributes r) {
        service.transportDetails(id,dispatch,fields,p.getName());
        r.addFlashAttribute("success","Datos de transporte guardados"+("true".equals(fields.get("saveAsDefault"))?" como perfil habitual":""));
        return back(id);
    }
    @GetMapping("/tarifas")
    String prices(@RequestParam(required=false) Long cliente,@RequestParam(defaultValue="") String q,Principal p,Model m) {
        m.addAttribute("customers",service.customers(p.getName()));m.addAttribute("selectedCustomer",cliente);m.addAttribute("query",q);
        m.addAttribute("products",cliente==null?List.of():service.prices(cliente,p.getName(),q));
        m.addAttribute("evidence",cliente==null?List.of():service.evidence(cliente,p.getName()));return "commercial-prices";
    }
    @GetMapping("/gestion")
    String management(Principal p,Model m) {m.addAttribute("customers",service.customers(p.getName()));m.addAttribute("sellers",service.sellers());m.addAttribute("issues",service.issues(p.getName()));return "commercial-management";}
    @PostMapping("/gestion/cartera")
    String assign(@RequestParam long customerId,@RequestParam(required=false) Long sellerId,Principal p) {service.assign(customerId,sellerId,p.getName());return "redirect:/modulos/comercial/gestion";}
    @PostMapping("/gestion/credito")
    String credit(@RequestParam long customerId,@RequestParam BigDecimal limit,@RequestParam int days,Principal p) {service.credit(customerId,limit,days,p.getName());return "redirect:/modulos/comercial/gestion";}
    @PostMapping("/gestion/precio")
    String price(@RequestParam long customerId,@RequestParam long productId,@RequestParam BigDecimal netPrice,
        @RequestParam String reason,Principal p) {service.approvePrice(customerId,productId,netPrice,reason,p.getName());return "redirect:/modulos/comercial/tarifas?cliente="+customerId;}
    @ExceptionHandler(IllegalArgumentException.class)
    String invalid(IllegalArgumentException e,HttpServletRequest request,RedirectAttributes r) {
        r.addFlashAttribute("error",e.getMessage());var match=java.util.regex.Pattern.compile("/modulos/comercial/(\\d+)(?:/.*)?").matcher(request.getRequestURI());
        return match.matches()?back(Long.parseLong(match.group(1))):"redirect:/modulos/comercial";
    }
    private String back(long id) {return "redirect:/modulos/comercial/"+id;}
}
