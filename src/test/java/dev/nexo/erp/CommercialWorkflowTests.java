package dev.nexo.erp;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import dev.nexo.erp.admin.AdminService;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.commercial.*;
import dev.nexo.erp.sales.SaleService;
import dev.nexo.erp.billing.ElectronicDocumentService;
import dev.nexo.erp.reports.ReportService;

@SpringBootTest @AutoConfigureMockMvc @Transactional
@TestPropertySource(properties={"app.security.bootstrap-password=prueba-segura-no-productiva",
    "app.security.mfa-master-key=clave-maestra-de-prueba-nexo-2026-segura","app.import-root="})
class CommercialWorkflowTests {
    @Autowired CommercialService commercial;
    @Autowired CatalogService catalog;
    @Autowired SaleService sales;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired AdminService admin;
    @Autowired ElectronicDocumentService fiscal;
    @Autowired ReportService reports;
    private record Fixture(long customer,long other,long product,String seller){}
    private Fixture fixture() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String seller="seller-"+suffix;
        jdbc.sql("insert into app_user(username,display_name,password_hash) values (:u,'Vendedor','unused')").param("u",seller).update();
        jdbc.sql("insert into user_role(user_id,role_id) select u.id,r.id from app_user u,role r where u.username=:u and r.code='VENTAS'").param("u",seller).update();
        String doc="20"+String.format("%09d",Math.abs((long)suffix.hashCode())%1000000000);
        catalog.createCustomer("6",doc,"Cliente propio "+suffix,null,"Callao","administrador");
        long customer=catalog.customers(doc).getFirst().id();
        String doc2="10"+doc.substring(2);
        catalog.createCustomer("6",doc2,"Cliente ajeno "+suffix,null,"Callao","administrador");
        long other=catalog.customers(doc2).getFirst().id();
        long sellerId=catalog.userId(seller);commercial.assign(customer,sellerId,"administrador");
        catalog.createProduct("TEST-"+suffix,"Lava Dish 4 L","NIU",new BigDecimal("24.78"),true,new BigDecimal("30"),"administrador");
        long product=catalog.products("TEST-"+suffix,true).getFirst().id();
        jdbc.sql("update product set net_reference_price=21,volume_litres=4,price_origin='ESTIMATED' where id=:id").param("id",product).update();
        return new Fixture(customer,other,product,seller);
    }
    private long quote(Fixture f,boolean approved,String condition) {
        if(approved)commercial.approvePrice(f.customer,f.product,new BigDecimal("21"),"Tarifa acordada","administrador");
        long id=commercial.create(f.customer,LocalDate.now().plusDays(2),"Callao",condition,"",f.seller);
        commercial.line(id,f.product,new BigDecimal("10"),null,f.seller);return id;
    }
    private void confirm(long id,String seller) {
        commercial.transition(id,"send","",null,seller);
        commercial.transition(id,"confirm","Cliente confirma por WhatsApp",LocalDate.now().plusDays(2),seller);
    }
    @Test void portfolioIsEnforcedForReadsWritesAndLegacyCustomerRoutes() throws Exception {
        var f=fixture();
        assertThat(commercial.customers(f.seller)).extracting(x->((Number)x.get("id")).longValue()).containsExactly(f.customer);
        assertThatThrownBy(()->commercial.prices(f.other,f.seller,"")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->commercial.create(f.other,null,"","CONTADO","",f.seller)).isInstanceOf(AccessDeniedException.class);
        mvc.perform(get("/modulos/clientes/"+f.other+"/editar").with(user(f.seller).authorities(()->"CUSTOMER_MANAGE"))).andExpect(status().isForbidden());
        mvc.perform(post("/modulos/clientes/"+f.other+"/estado").with(user(f.seller).authorities(()->"CUSTOMER_MANAGE")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/modulos/comprobantes").with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().isForbidden());
        mvc.perform(get("/modulos/comercial/tarifas").param("cliente",String.valueOf(f.other)).with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().isForbidden());
        mvc.perform(post("/modulos/comercial").with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().isForbidden());
    }
    @Test void estimatesNeedApprovalAndCustomerPriceChangesDoNotRewriteQuotes() {
        var f=fixture();long id=quote(f,false,"CONTADO");
        assertThat(commercial.order(id,f.seller).get("total")).isEqualTo(new BigDecimal("247.80"));
        assertThatThrownBy(()->commercial.transition(id,"send","",null,f.seller)).hasMessageContaining("aprobacion");
        assertThatThrownBy(()->commercial.transition(id,"approve","Yo apruebo",null,f.seller)).isInstanceOf(AccessDeniedException.class);
        commercial.transition(id,"approve","Gerencia autoriza esta oferta",null,"administrador");
        commercial.approvePrice(f.customer,f.product,new BigDecimal("25"),"Nueva tarifa","administrador");
        assertThat((BigDecimal)commercial.lines(id,f.seller).getFirst().get("net_price")).isEqualByComparingTo("21");
        confirm(id,f.seller);
        assertThatThrownBy(()->commercial.line(id,f.product,BigDecimal.ONE,BigDecimal.ONE,f.seller)).hasMessageContaining("estado");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("30");
    }
    @Test void singleEmissionActionGeneratesXmlAndPreservesDuplicateAndPermissionGuards() throws Exception {
        var f=fixture();long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        long sale=((Number)commercial.dispatches(id,f.seller).getFirst().get("sale_id")).longValue();
        long document=sales.detail(sale).header().electronicDocumentId();
        mvc.perform(post("/modulos/comprobantes/emitidos/"+document+"/emitir").with(csrf()).with(user(f.seller).authorities(()->"DOCUMENT_VIEW"))).andExpect(status().isForbidden());
        assertThat(fiscal.emit(document,"administrador").status()).isEqualTo(dev.nexo.erp.billing.FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        assertThat(fiscal.issuedDocument(document).sha256()).isNotBlank();
        jdbc.sql("update electronic_document set status='ACCEPTED' where id=:id").param("id",document).update();
        assertThatThrownBy(()->fiscal.emit(document,"administrador")).hasMessageContaining("no puede reenviarse");
        jdbc.sql("update electronic_document set status='SUBMISSION_UNKNOWN' where id=:id").param("id",document).update();
        assertThatThrownBy(()->fiscal.emit(document,"administrador")).hasMessageContaining("conciliaci");
    }
    @Test void partialDispatchBillsOnlySelectedQuantityAndRetriesAreIdempotent() {
        var f=fixture();long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        String key=UUID.randomUUID().toString();
        long d=commercial.prepare(id,List.of(line),List.of(new BigDecimal("4")),key,"administrador");
        assertThat(commercial.prepare(id,List.of(line),List.of(new BigDecimal("4")),key,"administrador")).isEqualTo(d);
        var dispatch=commercial.dispatches(id,f.seller).getFirst();long sale=((Number)dispatch.get("sale_id")).longValue();
        assertThat(sales.detail(sale).header().subtotal()).isEqualByComparingTo("84");
        assertThat(sales.detail(sale).header().total()).isEqualByComparingTo("99.12");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("30");
        assertThat(commercial.order(id,f.seller).get("status")).isEqualTo("PARTIAL");
        assertThatThrownBy(()->commercial.prepare(id,List.of(line),List.of(new BigDecimal("7")),UUID.randomUUID().toString(),"administrador")).hasMessageContaining("saldo");
        assertThatThrownBy(()->commercial.depart(id,d,"administrador")).hasMessageContaining("guia");
        commercial.externalGuide(id,d,"T001-123","","ABC123",LocalDate.now(),"administrador");
        assertThatThrownBy(()->commercial.depart(id,d,"administrador")).hasMessageContaining("aceptacion fiscal");
        jdbc.sql("update electronic_document set status='ACCEPTED' where sale_id=:s").param("s",sale).update();
        commercial.depart(id,d,"administrador");commercial.depart(id,d,"administrador");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("26");
        commercial.transition(id,"close","Cliente no requiere saldo",null,"administrador");
        assertThat(commercial.order(id,f.seller).get("status")).isEqualTo("CLOSED");
    }
    @Test void dispatchDefaultsCompanyAddressAndReusesSavedTransportProfile() {
        var f=fixture();long first=quote(f,true,"CONTADO");confirm(first,f.seller);
        long firstLine=((Number)commercial.lines(first,f.seller).getFirst().get("id")).longValue();
        long firstDispatch=commercial.prepare(first,List.of(firstLine),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        var initial=commercial.dispatches(first,"administrador").getFirst();
        assertThat(initial.get("departure_address")).isEqualTo(commercial.companyAddress("administrador"));
        assertThat(initial.get("arrival_address")).isEqualTo("Callao");
        commercial.transportDetails(first,firstDispatch,new HashMap<>(Map.ofEntries(
            Map.entry("departureAddress",commercial.companyAddress("administrador")),Map.entry("arrivalAddress","Callao"),
            Map.entry("mode","PRIVADO"),Map.entry("carrierRuc","00000000103"),Map.entry("carrierName","Transporte Nexo"),
            Map.entry("driverName","Conductor habitual"),Map.entry("driverDocument","12345678"),Map.entry("driverLicense","Q12345678"),
            Map.entry("vehiclePlate","ABC123"),Map.entry("vehicleBrand","Toyota"),Map.entry("grossWeight",""),Map.entry("packages",""),
            Map.entry("saveAsDefault","true"),Map.entry("profileName","Reparto principal"))),"administrador");
        assertThat(commercial.transportProfiles("administrador")).singleElement().satisfies(profile->{
            assertThat(profile.get("profile_name")).isEqualTo("Reparto principal");
            assertThat(profile.get("is_default")).isEqualTo(true);
        });
        long second=quote(f,true,"CONTADO");confirm(second,f.seller);
        long secondLine=((Number)commercial.lines(second,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(second,List.of(secondLine),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        var reused=commercial.dispatches(second,"administrador").getFirst();
        assertThat(reused.get("carrier_name")).isEqualTo("Transporte Nexo");
        assertThat(reused.get("vehicle_plate")).isEqualTo("ABC123");
        assertThat(reused.get("departure_address")).isEqualTo(commercial.companyAddress("administrador"));
    }
    @Test void creditRequiresManagementApprovalAndEnforcesLimit() {
        var f=fixture();long id=quote(f,true,"CREDITO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        assertThatThrownBy(()->commercial.credit(f.customer,new BigDecimal("1000"),30,f.seller)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador")).hasMessageContaining("Credito");
        commercial.credit(f.customer,new BigDecimal("20"),30,"administrador");
        assertThatThrownBy(()->commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador")).hasMessageContaining("Credito");
        commercial.credit(f.customer,new BigDecimal("1000"),30,"administrador");
        commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        assertThat(commercial.dispatches(id,"administrador")).hasSize(1);
    }
    @Test void reservationsAreInformativeAndDoNotBlockOtherOrders() {
        var f=fixture();long a=quote(f,true,"CONTADO");commercial.line(a,f.product,new BigDecimal("25"),null,f.seller);confirm(a,f.seller);
        long b=quote(f,true,"CONTADO");confirm(b,f.seller);
        assertThat(commercial.order(b,f.seller).get("status")).isEqualTo("CONFIRMED");
        long line=((Number)commercial.lines(b,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(b,List.of(line),List.of(new BigDecimal("6")),UUID.randomUUID().toString(),"administrador");
        assertThat(commercial.dispatches(b,f.seller)).hasSize(1);
    }
    @Test void commercialViewsRenderRealDataAndProtectPrintBeforeApproval() throws Exception {
        var f=fixture();long id=quote(f,true,"CONTADO");
        for(String path:List.of("/modulos/comercial","/modulos/comercial/nueva","/modulos/comercial/"+id,
            "/modulos/comercial/tarifas?cliente="+f.customer,"/modulos/comercial/gestion"))
            mvc.perform(get(path).with(user("administrador").authorities(()->"QUOTE_MANAGE",()->"PRICE_APPROVE"))).andExpect(status().isOk());
        mvc.perform(get("/modulos/comercial/"+id+"/imprimir").with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().is3xxRedirection());
        confirm(id,f.seller);
        mvc.perform(get("/modulos/comercial/"+id+"/imprimir").with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().isOk());
    }
    @Test void partialPreparationWithoutReservationDoesNotReserveUnavailableRemainder() {
        var f=fixture();long first=quote(f,true,"CONTADO");
        commercial.line(first,f.product,new BigDecimal("25"),null,f.seller);confirm(first,f.seller);
        long partial=quote(f,true,"CONTADO");confirm(partial,f.seller);
        long line=((Number)commercial.lines(partial,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(partial,List.of(line),new ArrayList<>(List.of(new BigDecimal("3"))),UUID.randomUUID().toString(),"administrador");
        assertThat(commercial.order(partial,f.seller).get("stock_reserved")).isEqualTo(false);
        long another=quote(f,true,"CONTADO");commercial.line(another,f.product,new BigDecimal("2"),null,f.seller);confirm(another,f.seller);
        assertThat(commercial.order(another,f.seller).get("status")).isEqualTo("CONFIRMED");
        commercial.transition(partial,"reserve","",null,"administrador");
        assertThat(commercial.order(partial,f.seller).get("stock_reserved")).isEqualTo(false);
    }
    @Test void activeSessionsAreRevokedAfterAccountOrRoleChanges() throws Exception {
        var f=fixture();
        var response=mvc.perform(get("/modulos/comercial").with(user(f.seller).authorities(()->"QUOTE_MANAGE")))
            .andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)response.getRequest().getSession(false);
        long role=jdbc.sql("select id from role where code='CONSULTA'").query(Long.class).single();
        admin.changeRole(catalog.userId(f.seller),role,"administrador");
        mvc.perform(get("/modulos/comercial").session(session)).andExpect(redirectedUrl("/login?expired"));
        var second=mvc.perform(get("/modulos/comercial").with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andReturn();
        admin.toggleUser(catalog.userId(f.seller),"administrador");
        mvc.perform(get("/modulos/comercial").session((MockHttpSession)second.getRequest().getSession(false)))
            .andExpect(redirectedUrl("/login?expired"));
    }
    @Test void paymentsAreIdempotentAndRejectFractionalCents() {
        var f=fixture();long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        long sale=((Number)commercial.dispatches(id,"administrador").getFirst().get("sale_id")).longValue();
        String key=UUID.randomUUID().toString();
        sales.registerPayment(sale,new BigDecimal("10"),"TRANSFER","DEP-123",key,"administrador");
        sales.registerPayment(sale,new BigDecimal("10"),"TRANSFER","DEP-123",key,"administrador");
        assertThat(sales.detail(sale).payments()).hasSize(1);
        assertThatThrownBy(()->sales.registerPayment(sale,new BigDecimal("10"),"TRANSFER","DEP-123",UUID.randomUUID().toString(),"administrador"))
            .hasMessageContaining("referencia");
        assertThatThrownBy(()->sales.registerPayment(sale,new BigDecimal("0.001"),"CASH",null,UUID.randomUUID().toString(),"administrador"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellingUnshippedInvoiceReleasesAllocationWithoutInventingStock() {
        var f=fixture();long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        long dispatch=commercial.prepare(id,List.of(line),List.of(new BigDecimal("10")),UUID.randomUUID().toString(),"administrador");
        long sale=((Number)commercial.dispatches(id,"administrador").getFirst().get("sale_id")).longValue();
        fiscal.requestCancellation(sales.detail(sale).header().electronicDocumentId(),"Preparacion anulada por prueba","administrador");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("30");
        assertThat(commercial.lines(id,f.seller).getFirst().get("dispatched_quantity")).isEqualTo(new BigDecimal("0.0000"));
        assertThat(commercial.order(id,f.seller).get("status")).isEqualTo("CONFIRMED");
        assertThat(commercial.dispatches(id,f.seller).getFirst().get("status")).isEqualTo("CANCELLED");
        assertThatThrownBy(()->commercial.depart(id,dispatch,"administrador")).hasMessageContaining("preparada");
        assertThatThrownBy(()->sales.registerPayment(sale,BigDecimal.ONE,"CASH",null,"administrador")).hasMessageContaining("anulada");
    }
    @Test void exportsNeutralizeSpreadsheetFormulasFromCustomerNames() {
        var f=fixture();long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        commercial.prepare(id,List.of(line),List.of(BigDecimal.ONE),UUID.randomUUID().toString(),"administrador");
        jdbc.sql("update customer set legal_name='=2+2' where id=:id").param("id",f.customer).update();
        String csv=new String(reports.salesCsv(LocalDate.now(),LocalDate.now()),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=2+2\"");
    }
    @Test void customerSearchSupportsRucContactAndTokensWithoutLeakingPortfolios() throws Exception {
        var f=fixture();
        jdbc.sql("update customer set legal_name='Compania de Limpieza',phone='987654321',email='demo@example.invalid' where id=:id").param("id",f.customer).update();
        assertThat(commercial.findCustomers(f.seller,"limpieza compania")).extracting(CommercialService.CustomerOption::id).containsExactly(f.customer);
        assertThat(commercial.findCustomers(f.seller,"987654321")).hasSize(1);
        assertThat(commercial.findCustomers(f.seller,"demo@example.invalid")).hasSize(1);
        var other=catalog.customer(f.other);
        assertThat(commercial.findCustomers(f.seller,other.documentNumber())).isEmpty();
        mvc.perform(get("/modulos/comercial/clientes/buscar").param("q",catalog.customer(f.customer).documentNumber())
            .with(user(f.seller).authorities(()->"QUOTE_MANAGE"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(f.customer)).andExpect(jsonPath("$[0].credit_limit").doesNotExist());
        assertThat(commercial.findCustomers(f.seller,"%' or 1=1 --")).isEmpty();
    }
    @Test void packagedProductsRejectFractionalQuantitiesAtEveryWriteBoundary() {
        var f=fixture();long id=quote(f,true,"CONTADO");
        assertThatThrownBy(()->commercial.line(id,f.product,new BigDecimal("1.5"),null,f.seller)).hasMessageContaining("entero");
        assertThatThrownBy(()->catalog.insertMovement(f.product,new BigDecimal("1.25"),"INITIAL","TEST","1",null,null,"test","administrador")).hasMessageContaining("entero");
        commercial.line(id,f.product,new BigDecimal("2.0000"),null,f.seller);confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        assertThatThrownBy(()->commercial.prepare(id,List.of(line),List.of(new BigDecimal("0.5")),UUID.randomUUID().toString(),"administrador")).hasMessageContaining("entero");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("30");
    }
    @Test void retiredOneLitreCannotBeReactivatedOrAddedToQuotes() {
        var f=fixture();long id=quote(f,true,"CONTADO");
        jdbc.sql("update product set active=false,volume_litres=1 where id=:id").param("id",f.product).update();
        assertThatThrownBy(()->catalog.toggle("product",f.product,"administrador")).hasMessageContaining("retirada");
        assertThatThrownBy(()->commercial.line(id,f.product,BigDecimal.ONE,null,f.seller)).hasMessageContaining("no disponible");
    }
    @Test void zeroStockAllowsInvoiceAndNegativeDispatchWithAuditButStillRequiresFiscalAcceptance() throws Exception {
        var f=fixture();catalog.adjustStock(f.product,new BigDecimal("30"),"OUT",null,null,"Prueba saldo cero","administrador");
        long id=quote(f,true,"CONTADO");confirm(id,f.seller);
        long line=((Number)commercial.lines(id,f.seller).getFirst().get("id")).longValue();
        long dispatch=commercial.prepare(id,List.of(line),List.of(new BigDecimal("10")),UUID.randomUUID().toString(),"administrador");
        long sale=((Number)commercial.dispatches(id,"administrador").getFirst().get("sale_id")).longValue();
        commercial.externalGuide(id,dispatch,"EG01-123","","ABC123",LocalDate.now(),"administrador");
        assertThatThrownBy(()->commercial.depart(id,dispatch,"administrador")).hasMessageContaining("aceptacion fiscal");
        jdbc.sql("update electronic_document set status='ACCEPTED' where sale_id=:s").param("s",sale).update();
        commercial.depart(id,dispatch,"administrador");commercial.depart(id,dispatch,"administrador");
        assertThat(catalog.stock(f.product)).isEqualByComparingTo("-10");
        assertThat(commercial.activity(id,"administrador")).anyMatch(a->"NEGATIVE_STOCK_NOTICE".equals(a.get("action")));
        mvc.perform(get("/modulos/comercial/"+id+"/despachos/"+dispatch+"/imprimir").with(user(f.seller).authorities(()->"QUOTE_MANAGE")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("NO SUSTITUYE LA GRE")));
    }
    @Test void contextCreatesCustomerInOwnPortfolioAndRejectsCsrfAndSupplierPrivilegeEscalation() throws Exception {
        var f=fixture();String number="10"+String.format("%09d",System.nanoTime()%1000000000L);
        mvc.perform(post("/modulos/contexto/customer").with(user(f.seller).authorities(()->"QUOTE_MANAGE")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/modulos/contexto/supplier").with(user(f.seller).authorities(()->"QUOTE_MANAGE")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/modulos/contexto/customer").param("documentType","6").param("documentNumber",number).param("legalName","Cliente contextual")
            .with(user(f.seller).authorities(()->"QUOTE_MANAGE")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.document").value(number));
        assertThat(commercial.findCustomers(f.seller,number)).hasSize(1);
        mvc.perform(post("/modulos/contexto/identidad").param("documentType","6").param("documentNumber","http://127.0.0.1")
            .with(user(f.seller).authorities(()->"QUOTE_MANAGE")).with(csrf())).andExpect(status().isBadRequest());
    }
}
