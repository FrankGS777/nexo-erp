package dev.nexo.erp.web;

import java.security.Principal;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import dev.nexo.erp.catalog.*;
import dev.nexo.erp.commercial.CommercialAccess;

@RestController
@RequestMapping("/modulos/contexto")
public class ContextEntitiesController {
    private final CatalogService catalog;
    private final CommercialAccess access;
    private final IdentityLookupService lookup;
    private final JdbcClient jdbc;
    public ContextEntitiesController(CatalogService catalog,CommercialAccess access,IdentityLookupService lookup,JdbcClient jdbc) {
        this.catalog=catalog;this.access=access;this.lookup=lookup;this.jdbc=jdbc;
    }
    @GetMapping("/permisos")
    Map<String,Boolean> permissions(Principal p) {return Map.of("customer",access.has(p.getName(),"CUSTOMER_MANAGE"),"supplier",access.has(p.getName(),"PURCHASE_MANAGE"));}
    @PostMapping("/identidad")
    IdentityLookupService.Result identity(@RequestParam String documentType,@RequestParam String documentNumber,Principal p) {
        return lookup.lookup(documentType,documentNumber,p.getName());
    }
    @PostMapping("/{entity:customer|supplier}") @Transactional
    Map<String,Object> create(@PathVariable String entity,@RequestParam Map<String,String> f,Principal p) {
        boolean customer="customer".equals(entity);access.require(p.getName(),customer?"CUSTOMER_MANAGE":"PURCHASE_MANAGE");
        Map<String,Integer> limits=Map.of("documentType",1,"documentNumber",20,"legalName",220,"tradeName",220,"contactName",160,"email",254,"phone",25,"address",300,"ubigeo",6,"countryCode",2);
        for(var limit:limits.entrySet())if(f.getOrDefault(limit.getKey(),"").length()>limit.getValue())throw new IllegalArgumentException("Campo demasiado largo: "+limit.getKey());
        String type=f.getOrDefault("documentType","6"),number=f.getOrDefault("documentNumber","").strip();
        if(customer)catalog.createCustomer(type,number,f.get("legalName"),f.get("tradeName"),f.get("contactName"),f.get("email"),f.get("phone"),f.get("address"),f.get("ubigeo"),"PE",p.getName());
        else catalog.createSupplier(type,number,f.get("legalName"),f.get("tradeName"),f.get("contactName"),f.get("email"),f.get("phone"),f.get("address"),f.get("ubigeo"),"PE",p.getName());
        long id=jdbc.sql("select id from "+(customer?"customer":"supplier")+" where document_type=:t and document_number=:n").param("t",type).param("n",number).query(Long.class).single();
        return Map.of("id",id,"name",f.get("legalName"),"document",number,"address",f.getOrDefault("address",""));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate(){return ResponseEntity.status(409).body(Map.of("message","No se pudo registrar: verifica los datos o solicita a administracion revisar el documento existente."));}
}
