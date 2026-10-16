package dev.nexo.erp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.billing.ElectronicDocumentService;
import dev.nexo.erp.billing.FiscalGateway;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.admin.AdminService;
import dev.nexo.erp.purchases.PurchaseService;
import dev.nexo.erp.sales.SaleService;
import dev.nexo.erp.security.AccountSecurityService;
import dev.nexo.erp.security.MfaService;
import dev.nexo.erp.security.MfaSession;
import dev.nexo.erp.security.TotpService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.security.bootstrap-password=prueba-segura-no-productiva",
    "app.security.mfa-master-key=clave-maestra-de-prueba-nexo-2026-segura",
    "app.import-root="
})
class NexoErpApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private SaleService sales;

    @Autowired
    private PurchaseService purchases;

    @Autowired
    private ElectronicDocumentService electronicDocuments;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private AdminService admin;

    @Autowired
    private MfaService mfa;

    @Autowired
    private TotpService totp;

    @Autowired
    private AccountSecurityService accountSecurity;

    @Test
    void redirectsAnonymousVisitorsToLogin() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void rendersDashboardForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/").with(user("administrador").authorities(() -> "DASHBOARD_VIEW")))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard"));
    }

    @Test
    void rendersHistoricalDocumentsForAuthorizedUser() throws Exception {
        mockMvc.perform(get("/modulos/comprobantes")
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW")))
            .andExpect(status().isOk())
            .andExpect(view().name("documents"));
    }

    @Test
    void authenticatesPersistedBootstrapAdministrator() throws Exception {
        mockMvc.perform(formLogin()
                .user("administrador")
                .password("prueba-segura-no-productiva"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    void rejectsIncorrectPassword() throws Exception {
        mockMvc.perform(formLogin()
                .user("administrador")
                .password("incorrecta"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void rendersEveryFunctionalModuleWithItsPermission() throws Exception {
        assertModule("/modulos/clientes", "CUSTOMER_MANAGE", "customers");
        assertModule("/modulos/clientes/nuevo", "CUSTOMER_MANAGE", "customer-form");
        assertModule("/modulos/inventario", "INVENTORY_MANAGE", "inventory");
        assertModule("/modulos/inventario/existencias", "INVENTORY_MANAGE", "inventory-stock");
        assertModule("/modulos/inventario/lotes", "INVENTORY_MANAGE", "inventory-lots");
        assertModule("/modulos/inventario/kardex", "INVENTORY_MANAGE", "inventory-kardex");
        assertModule("/modulos/inventario/ajustes", "INVENTORY_MANAGE", "inventory-adjustment");
        assertModule("/modulos/inventario/productos", "INVENTORY_MANAGE", "products");
        assertModule("/modulos/inventario/productos/nuevo", "INVENTORY_MANAGE", "product-form");
        assertModule("/modulos/compras", "PURCHASE_MANAGE", "purchases");
        assertModule("/modulos/compras/nueva", "PURCHASE_MANAGE", "purchase-form");
        assertModule("/modulos/proveedores", "PURCHASE_MANAGE", "suppliers");
        assertModule("/modulos/proveedores/nuevo", "PURCHASE_MANAGE", "supplier-form");
        assertModule("/modulos/ventas", "SALES_MANAGE", "sales");
        mockMvc.perform(get("/modulos/ventas/nueva").with(user("administrador").authorities(() -> "SALES_MANAGE")))
            .andExpect(redirectedUrl("/modulos/comercial/nueva"));
        assertModule("/modulos/comprobantes/emitidos", "DOCUMENT_VIEW", "issued-documents");
        assertModule("/modulos/comprobantes/recibidos", "DOCUMENT_VIEW", "received-documents");
        assertModule("/modulos/reportes", "REPORT_VIEW", "reports");
        assertModule("/modulos/configuracion", "SECURITY_ADMIN", "settings");
        mockMvc.perform(get("/mi-cuenta").with(user("administrador")))
            .andExpect(status().isOk()).andExpect(view().name("account"));
    }

    @Test
    @Transactional
    void rejectsDirectSaleWithoutCurrentPriceButAllowsAgreedOrderPrice() {
        catalog.createCustomer("6", "00000000000", "Cliente de comprobacion", null, "Callao", "administrador");
        long customer = catalog.customers("00000000000").getFirst().id();
        catalog.createProduct("PRICE-PENDING", "Precio pendiente", "NIU", new BigDecimal("11.80"),
            false, BigDecimal.ZERO, "administrador");
        long product = catalog.products("PRICE-PENDING", true).getFirst().id();
        jdbc.sql("update product set sale_price=0 where id=:id").param("id",product).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sales.create(customer,"01",LocalDate.now(),
            List.of(product),List.of(BigDecimal.ONE),"CASH",null,"administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no tiene precio de venta vigente");
        long sale = sales.createFromOrder(customer,List.of(product),List.of(BigDecimal.ONE),
            List.of(new BigDecimal("10.00")),null,"administrador");
        org.assertj.core.api.Assertions.assertThat(sales.detail(sale).header().total()).isEqualByComparingTo("11.80");
    }

    @Test
    @Transactional
    void completesSaleStockPaymentAdjustmentAndUblWorkflow() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String customerNumber = "20" + suffix.substring(Math.max(0, suffix.length() - 9));
        catalog.createCustomer("6", customerNumber, "Cliente de prueba", "demo@example.invalid", "Callao",
            "administrador");
        long customerId = catalog.customers(customerNumber).getFirst().id();
        String sku = "TEST-" + suffix;
        catalog.createProduct(sku, "Producto de prueba", "NIU", new BigDecimal("11.80"),
            true, new BigDecimal("10"), "administrador");
        long productId = catalog.products(sku, true).getFirst().id();

        long saleId = sales.create(customerId, "01", LocalDate.now(), List.of(productId),
            List.of(new BigDecimal("2")), "TRANSFER", "Prueba integrada", "administrador");
        var detail = sales.detail(saleId);
        org.assertj.core.api.Assertions.assertThat(detail.header().total()).isEqualByComparingTo("23.60");
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("8");

        sales.registerPayment(saleId, new BigDecimal("23.60"), "TRANSFER", "OP-TEST", "administrador");
        sales.createAdjustment(saleId, "07", "01", "Anulación de prueba", new BigDecimal("23.60"),
            "administrador");
        sales.createAdjustment(saleId, "08", "02", "Aumento de valor de prueba", new BigDecimal("30.00"),
            "administrador");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sales.createAdjustment(
                saleId, "08", "10", "Código ajeno al catálogo de débito", BigDecimal.ONE, "administrador"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nota de débito");

        long documentId = sales.detail(saleId).header().electronicDocumentId();
        var generated = electronicDocuments.generate(documentId, "administrador");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).label())
            .startsWith("F001-");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.documentEvents(documentId))
            .isNotEmpty();
        mockMvc.perform(get("/modulos/comprobantes/emitidos/" + documentId)
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW")))
            .andExpect(status().isOk())
            .andExpect(view().name("issued-document-detail"));
        String xml = electronicDocuments.xmlResource(documentId).getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(generated.sha256()).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(xml).contains("<Invoice", customerNumber, "F001-");
        var result = electronicDocuments.submit(documentId, "administrador");
        org.assertj.core.api.Assertions.assertThat(result.status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "select status from electronic_document where id = :id")
            .param("id", documentId).query(String.class).single()).isEqualTo("GENERATED_UNSIGNED");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "select status from fiscal_submission_attempt where electronic_document_id = :id")
            .param("id", documentId).query(String.class).single()).isEqualTo("BLOCKED");
        jdbc.sql("update electronic_document set status='REJECTED', error_code='soap-env:Client.0111' where id=:id")
            .param("id", documentId).update();
        jdbc.sql("update fiscal_submission_attempt set status='REJECTED', error_code='soap-env:Client.0111' where electronic_document_id=:id")
            .param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isTrue();
        String blockedPage = mockMvc.perform(get("/modulos/comprobantes/emitidos/" + documentId)
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW", () -> "DOCUMENT_ISSUE")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(blockedPage).contains("Reintentar envío", "Acceso de envío bloqueado");
        jdbc.sql("update fiscal_submission_attempt set response_sha256='cdr-evidence' where electronic_document_id=:id")
            .param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> electronicDocuments.submit(documentId, "administrador"))
            .isInstanceOf(IllegalArgumentException.class);
        jdbc.sql("update fiscal_submission_attempt set response_sha256=null, status='SUBMISSION_UNKNOWN' where electronic_document_id=:id")
            .param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isFalse();
        jdbc.sql("update fiscal_submission_attempt set status='REJECTED' where electronic_document_id=:id")
            .param("id", documentId).update();
        jdbc.sql("update electronic_document set error_code='soap-env:Client.1033' where id=:id")
            .param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isFalse();
        jdbc.sql("update electronic_document set error_code='soap-env:Client.0111' where id=:id")
            .param("id", documentId).update();
        String labelBeforeRetry = electronicDocuments.issuedDocument(documentId).label();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.submit(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).label()).isEqualTo(labelBeforeRetry);
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isFalse();
        jdbc.sql("""
            update electronic_document set error_code='soap-env:Client.0111', sent_at=current_timestamp,
                created_at=timestamp with time zone '2026-09-29 21:42:00+00'
            where id=:id
            """).param("id", documentId).update();
        jdbc.sql("""
            update fiscal_submission_attempt set error_code='soap-env:Client.0111'
            where electronic_document_id=:id
            """).param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.isProfileBlocked(documentId)).isTrue();
        String retryBlockedPage = mockMvc.perform(get("/modulos/comprobantes/emitidos/" + documentId)
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW", () -> "DOCUMENT_ISSUE")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(retryBlockedPage)
            .contains("Reintentar envío", "Acceso de envío bloqueado", "Acceso no autorizado por SUNAT", "29/09/2026 16:42")
            .doesNotContain("XML listo para enviar", ">Emitir factura<", ">Enviado<");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.submit(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).label()).isEqualTo(labelBeforeRetry);
        jdbc.sql("update electronic_document set status = 'SUBMISSION_UNKNOWN' where id = :id")
            .param("id", documentId).update();
        FiscalGateway queryGateway = org.mockito.Mockito.mock(FiscalGateway.class);
        org.mockito.Mockito.when(queryGateway.reconcile(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new FiscalGateway.SubmissionResult(FiscalGateway.SubmissionResult.Status.SUBMISSION_UNKNOWN,
                null, "soap-env:Client.0111", "La consulta no confirma el estado fiscal"));
        var queryDocuments = new ElectronicDocumentService(jdbc,
            new dev.nexo.erp.billing.UblDocumentGenerator(),
            new dev.nexo.erp.billing.VoidedDocumentGenerator(),
            new dev.nexo.erp.billing.DailySummaryGenerator(), queryGateway,
            new dev.nexo.erp.billing.CdrReader(), sales, catalog, transactionManager,
            System.getProperty("java.io.tmpdir"));
        org.assertj.core.api.Assertions.assertThat(queryDocuments.reconcile(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.SUBMISSION_UNKNOWN);
        org.assertj.core.api.Assertions.assertThat(queryDocuments.issuedDocument(documentId).status())
            .isEqualTo("SUBMISSION_UNKNOWN");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryDocuments.requestCancellation(
            documentId, "Consulta sin autorización", "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concilia");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryDocuments.submit(documentId, "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conciliación pendiente");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.reconcile(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "select status from electronic_document where id = :id")
            .param("id", documentId).query(String.class).single()).isEqualTo("SUBMISSION_UNKNOWN");
        var cdr = new MockMultipartFile("file", "R-test.xml", "application/xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <ApplicationResponse xmlns="urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2"
              xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
              xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:Note>4266 - Observacion de prueba &lt;script&gt;</cbc:Note>
              <cac:DocumentResponse><cac:Response>
                <cbc:ReferenceID>F001-1</cbc:ReferenceID>
                <cbc:ResponseCode>0</cbc:ResponseCode>
                <cbc:Description>Aceptado en prueba</cbc:Description>
              </cac:Response></cac:DocumentResponse>
            </ApplicationResponse>
            """.getBytes(StandardCharsets.UTF_8));
        for (String protectedStatus : List.of("SUBMITTING", "SUBMISSION_UNKNOWN", "PROCESSING",
            "VOID_SUBMITTING", "VOID_PROCESSING", "CANCELLED", "CANCELLED_LOCAL", "ACCEPTED", "OBSERVED")) {
            jdbc.sql("update electronic_document set status=:status where id=:id")
                .param("status", protectedStatus).param("id", documentId).update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> electronicDocuments.registerCdr(documentId, cdr, "administrador"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No se puede reemplazar");
            org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).status()).isEqualTo(protectedStatus);
            org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).cdrSha256()).isNull();
        }
        jdbc.sql("update electronic_document set status='GENERATED_UNSIGNED' where id=:id").param("id", documentId).update();
        var cdrResult = electronicDocuments.registerCdr(documentId, cdr, "administrador");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.cdrObservations(documentId))
            .containsExactly("4266 - Observacion de prueba <script>");
        org.assertj.core.api.Assertions.assertThat(cdrResult.status()).isEqualTo("CDR_RECORDED_UNVERIFIED");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.cdrDownloadName(documentId))
            .endsWith(".xml");
        String preservedCdr = electronicDocuments.issuedDocument(documentId).cdrSha256();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.reconcile(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).status())
            .isEqualTo("CDR_RECORDED_UNVERIFIED");
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).cdrSha256())
            .isEqualTo(preservedCdr);
        org.assertj.core.api.Assertions.assertThat(queryDocuments.reconcile(documentId, "administrador").status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.SUBMISSION_UNKNOWN);
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).status())
            .isEqualTo("SUBMISSION_UNKNOWN");
        var expectedDocument = electronicDocuments.issuedDocument(documentId);
        org.mockito.Mockito.verify(queryGateway, org.mockito.Mockito.times(2)).reconcile(
            new FiscalGateway.DocumentReference(documentId, expectedDocument.type(),
                expectedDocument.series(), expectedDocument.number()));
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).cdrSha256())
            .isEqualTo(preservedCdr);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> electronicDocuments.submit(documentId, "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conciliación pendiente");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> electronicDocuments.requestCancellation(
            documentId, "Consulta sin autorización", "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concilia");
        for (String protectedStatus : List.of("GENERATED_UNSIGNED", "REJECTED", "ACCEPTED", "OBSERVED", "CDR_RECORDED_UNVERIFIED")) {
            jdbc.sql("update electronic_document set status=:status where id=:id")
                .param("status", protectedStatus).param("id", documentId).update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> electronicDocuments.registerCdr(documentId, cdr, "administrador"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No se puede reemplazar");
            org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).cdrSha256()).isEqualTo(preservedCdr);
        }
        jdbc.sql("update electronic_document set status = 'ACCEPTED' where id = :id")
            .param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.requestCancellation(documentId,
            "Error de prueba integrada", "administrador")).contains("Solicitud de baja");
        long actionId = jdbc.sql("select max(id) from fiscal_action_request where electronic_document_id = :id")
            .param("id", documentId).query(Long.class).single();
        var voidResult = electronicDocuments.submitCancellation(actionId, "administrador");
        org.assertj.core.api.Assertions.assertThat(voidResult.status())
            .isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "select status from fiscal_action_request where id = :id")
            .param("id", actionId).query(String.class).single()).isEqualTo("PENDING");
        jdbc.sql("update fiscal_action_request set status='PROCESSING', provider_ticket='test-ticket' where id=:id")
            .param("id", actionId).update();
        jdbc.sql("update electronic_document set status='CANCELLED' where id=:id").param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryDocuments.pollCancellation(actionId, "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ya no tiene esta baja");
        org.mockito.Mockito.verify(queryGateway, org.mockito.Mockito.never())
            .poll(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(electronicDocuments.issuedDocument(documentId).status()).isEqualTo("CANCELLED");
        jdbc.sql("update fiscal_action_request set status='PENDING', provider_ticket=null where id=:id")
            .param("id", actionId).update();
        jdbc.sql("update electronic_document set status='VOID_REQUESTED' where id=:id").param("id", documentId).update();
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("8");
        mockMvc.perform(get("/modulos/ventas/" + saleId)
                .with(user("administrador").authorities(() -> "SALES_MANAGE")))
            .andExpect(status().isOk()).andExpect(view().name("sale-detail"));
        mockMvc.perform(get("/modulos/ventas/" + saleId + "/imprimir")
                .with(user("administrador").authorities(() -> "SALES_MANAGE")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/modulos/comprobantes/emitidos/"+documentId+"/imprimir"));
        mockMvc.perform(get("/modulos/comprobantes/emitidos/"+documentId+"/imprimir")
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW")))
            .andExpect(status().isOk()).andExpect(view().name("document-customer-print"));
        mockMvc.perform(get("/modulos/comprobantes/emitidos")
                .with(user("administrador").authorities(() -> "DOCUMENT_VIEW")))
            .andExpect(status().isOk()).andExpect(view().name("issued-documents"));
        jdbc.sql("update sale set currency_code = 'USD' where id = :id")
            .param("id", saleId).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sales.createAdjustment(
                saleId, "08", "02", "Cambio no permitido", BigDecimal.ONE, "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("otra moneda");
        jdbc.sql("update sale set currency_code = 'PEN' where id = :id")
            .param("id", saleId).update();
        jdbc.sql("update sale_line set igv_affectation_code = '20' where sale_id = :id")
            .param("id", saleId).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sales.createAdjustment(
                saleId, "08", "02", "Afectación no permitida", BigDecimal.ONE, "administrador"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("por ítem");
    }

    @Test
    void cancellationWaitsForSubmissionStateBeforeDeciding() throws Exception {
        long number = System.nanoTime();
        jdbc.sql("""
            insert into electronic_document(document_type,series,number,status)
            values ('01','FZ99',:number,'GENERATED_UNSIGNED')
            """).param("number", number).update();
        long documentId = jdbc.sql("select id from electronic_document where series='FZ99' and number=:number")
            .param("number", number).query(Long.class).single();
        var started = new java.util.concurrent.CountDownLatch(1);
        var cancellation = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("select id from electronic_document where id=:id for update")
                    .param("id", documentId).query(Long.class).single();
                cancellation.set(executor.submit(() -> {
                    started.countDown();
                    return electronicDocuments.requestCancellation(documentId, "Anulación concurrente", "administrador");
                }));
                try {
                    org.assertj.core.api.Assertions.assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> cancellation.get().get(400, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                jdbc.sql("update electronic_document set status='SUBMITTING' where id=:id")
                    .param("id", documentId).update();
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> cancellation.get().get(3, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThat(jdbc.sql("select status from electronic_document where id=:id")
                .param("id", documentId).query(String.class).single()).isEqualTo("SUBMITTING");
            org.assertj.core.api.Assertions.assertThat(jdbc.sql("select count(*) from electronic_document_event where electronic_document_id=:id")
                .param("id", documentId).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("delete from electronic_document_event where electronic_document_id=:id").param("id", documentId).update();
            jdbc.sql("delete from audit_event where resource_type='ELECTRONIC_DOCUMENT' and resource_id=:id")
                .param("id", String.valueOf(documentId)).update();
            jdbc.sql("delete from electronic_document where id=:id").param("id", documentId).update();
        }
    }

    @Test
    @Transactional
    void completesSupplierPurchaseDetailAndCancellationWorkflow() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String supplierNumber = "20" + suffix.substring(Math.max(0, suffix.length() - 9));
        catalog.createSupplier("6", supplierNumber, "Proveedor de prueba", "demo@example.invalid",
            "999111222", "Callao", "administrador");
        long supplierId = catalog.suppliers(supplierNumber, true).getFirst().id();
        catalog.updateSupplier(supplierId, "Proveedor actualizado", "demo@example.invalid",
            "999111333", "Ventanilla", "administrador");

        String sku = "BUY-" + suffix;
        catalog.createProduct(sku, "Insumo de prueba", "NIU", new BigDecimal("17.70"),
            true, BigDecimal.ZERO, "administrador");
        long productId = catalog.products(sku, true).getFirst().id();
        long purchaseId = purchases.create(supplierId, "01", "F999", suffix, LocalDate.now(),
            List.of(productId), List.of(new BigDecimal("5")), List.of(new BigDecimal("11.80")),
            List.of("LOTE-TEST"), List.of(LocalDate.now().plusYears(1)), "Ingreso de prueba",
            "administrador");

        var detail = purchases.detail(purchaseId);
        org.assertj.core.api.Assertions.assertThat(detail.header().supplierName())
            .isEqualTo("Proveedor actualizado");
        org.assertj.core.api.Assertions.assertThat(detail.lines()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(detail.header().total()).isEqualByComparingTo("59.00");
        org.assertj.core.api.Assertions.assertThat(detail.header().inventoryStatus()).isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("0");
        purchases.receive(purchaseId, "administrador");
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("5");
        org.assertj.core.api.Assertions.assertThat(purchases.detail(purchaseId).header().inventoryStatus())
            .isEqualTo("RECEIVED");
        mockMvc.perform(get("/modulos/compras/" + purchaseId)
                .with(user("administrador").authorities(() -> "PURCHASE_MANAGE")))
            .andExpect(status().isOk()).andExpect(view().name("purchase-detail"));

        catalog.adjustStock(productId, new BigDecimal("7"), "OUT", null, null,
            "Salida permitida aun sin saldo suficiente", "administrador");
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("-2");
        purchases.cancel(purchaseId, "Documento duplicado de prueba", "administrador");
        org.assertj.core.api.Assertions.assertThat(purchases.detail(purchaseId).header().status())
            .isEqualTo("CANCELLED");
        org.assertj.core.api.Assertions.assertThat(catalog.stock(productId)).isEqualByComparingTo("-7");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                select count(*) from inventory_movement where reference_type = 'PURCHASE_CANCEL'
                  and reference_id = :id and movement_type = 'PURCHASE_REVERSAL'
                """).param("id", String.valueOf(purchaseId)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void enforcesTotpRecoveryReplayProtectionAndTemporaryPasswordChange() throws Exception {
        String username = "mfa" + System.nanoTime();
        String temporaryPassword = "Temporal2026Segura";
        String permanentPassword = "Definitiva2026Segura";
        long roleId = jdbc.sql("select id from role where code = 'ADMINISTRADOR'")
            .query(Long.class).single();
        admin.createUser(username, "Usuario MFA de prueba", username + "@example.test",
            temporaryPassword, roleId, true, "administrador");

        MvcResult primaryLogin = mockMvc.perform(formLogin()
                .user(username).password(temporaryPassword))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/seguridad/mfa"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) primaryLogin.getRequest().getSession(false);

        mockMvc.perform(get("/seguridad/mfa").session(session))
            .andExpect(status().isOk()).andExpect(view().name("mfa"));
        String secret = (String) session.getAttribute(MfaSession.PENDING_SECRET);
        org.assertj.core.api.Assertions.assertThat(secret).isNotBlank();

        String enrollmentCode = totp.codeAt(secret, Instant.now());
        mockMvc.perform(post("/seguridad/mfa/verificar").session(session).with(csrf())
                .param("code", enrollmentCode))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/seguridad/mfa/codigos-recuperacion"));
        List<String> recoveryCodes = (List<String>) session.getAttribute(MfaSession.RECOVERY_CODES);
        org.assertj.core.api.Assertions.assertThat(recoveryCodes).hasSize(8);
        org.assertj.core.api.Assertions.assertThat(mfa.status(username).enrolled()).isTrue();

        mockMvc.perform(get("/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/mi-cuenta?changeRequired"));

        MvcResult secondLogin = mockMvc.perform(formLogin()
                .user(username).password(temporaryPassword))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/seguridad/mfa"))
            .andReturn();
        MockHttpSession secondSession = (MockHttpSession) secondLogin.getRequest().getSession(false);
        String nextCode = totp.codeAt(secret, Instant.now().plusSeconds(30));
        mockMvc.perform(post("/seguridad/mfa/verificar").session(secondSession).with(csrf())
                .param("code", nextCode))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/mi-cuenta?changeRequired"));

        org.assertj.core.api.Assertions.assertThat(mfa.verify(username, nextCode, "127.0.0.1").valid())
            .as("un código TOTP no puede reutilizarse").isFalse();
        String recoveryCode = recoveryCodes.getFirst();
        org.assertj.core.api.Assertions.assertThat(mfa.verify(username, recoveryCode, "127.0.0.1").valid())
            .isTrue();
        org.assertj.core.api.Assertions.assertThat(mfa.verify(username, recoveryCode, "127.0.0.1").valid())
            .as("un código de recuperación se consume una sola vez").isFalse();
        for (int attempt = 0; attempt < 4; attempt++) {
            org.assertj.core.api.Assertions.assertThat(mfa.verify(username, "000000", "127.0.0.1").valid())
                .isFalse();
        }
        org.assertj.core.api.Assertions.assertThat(mfa.status(username).lockedUntil())
            .as("cinco fallos bloquean temporalmente el segundo factor")
            .isNotNull();

        accountSecurity.changePassword(username, temporaryPassword, permanentPassword,
            permanentPassword);
        org.assertj.core.api.Assertions.assertThat(accountSecurity.profile(username).mustChangePassword())
            .isFalse();
        mockMvc.perform(formLogin().user(username).password(permanentPassword))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/seguridad/mfa"));
    }

    private void assertModule(String path, String authority, String expectedView) throws Exception {
        mockMvc.perform(get(path).with(user("administrador").authorities(() -> authority)))
            .andExpect(status().isOk())
            .andExpect(view().name(expectedView));
    }
}
