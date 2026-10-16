package dev.nexo.erp.billing;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.sales.SaleService;

@Service
public class ElectronicDocumentService {

    private final JdbcClient jdbc;
    private final UblDocumentGenerator generator;
    private final VoidedDocumentGenerator voidGenerator;
    private final DailySummaryGenerator summaryGenerator;
    private final FiscalGateway gateway;
    private final CdrReader cdrReader;
    private final SaleService sales;
    private final CatalogService catalog;
    private final Path storageRoot;
    private final TransactionTemplate transactions;

    public ElectronicDocumentService(JdbcClient jdbc, UblDocumentGenerator generator,
                                     VoidedDocumentGenerator voidGenerator,
                                     DailySummaryGenerator summaryGenerator,
                                     FiscalGateway gateway, CdrReader cdrReader,
                                     SaleService sales, CatalogService catalog,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.document-storage}") String storageRoot) {
        this.jdbc = jdbc;
        this.generator = generator;
        this.voidGenerator = voidGenerator;
        this.summaryGenerator = summaryGenerator;
        this.gateway = gateway;
        this.cdrReader = cdrReader;
        this.sales = sales;
        this.catalog = catalog;
        this.transactions = new TransactionTemplate(transactionManager);
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public List<IssuedDocumentRow> issuedDocuments() {
        return jdbc.sql("""
                select ed.id, ed.document_type, ed.series, ed.number, ed.status, ed.xml_sha256,
                       ed.cdr_sha256, ed.xml_object_key, ed.provider_ticket, ed.error_code, ed.error_message,
                       ed.sent_at, ed.accepted_at, ed.created_at,
                       coalesce(c.legal_name, original_customer.legal_name) as customer_name,
                       coalesce(s.total, a.total) as total
                from electronic_document ed
                left join sale s on s.id = ed.sale_id
                left join customer c on c.id = s.customer_id
                left join adjustment_document a on a.id = ed.adjustment_id
                left join sale original_sale on original_sale.id = a.original_sale_id
                left join customer original_customer on original_customer.id = original_sale.customer_id
                order by ed.id desc
                limit 250
                """)
            .query((rs, row) -> new IssuedDocumentRow(rs.getLong("id"), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"), rs.getString("status"),
                rs.getString("xml_sha256"), rs.getString("cdr_sha256"), rs.getString("xml_object_key"), rs.getString("provider_ticket"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("customer_name"),
                rs.getBigDecimal("total")))
            .list();
    }

    public IssuedDocumentRow issuedDocument(long id) {
        return jdbc.sql("""
                select ed.id, ed.document_type, ed.series, ed.number, ed.status, ed.xml_sha256,
                       ed.cdr_sha256, ed.xml_object_key, ed.provider_ticket, ed.error_code, ed.error_message,
                       ed.sent_at, ed.accepted_at, ed.created_at,
                       coalesce(c.legal_name, original_customer.legal_name) as customer_name,
                       coalesce(s.total, a.total) as total
                from electronic_document ed
                left join sale s on s.id = ed.sale_id
                left join customer c on c.id = s.customer_id
                left join adjustment_document a on a.id = ed.adjustment_id
                left join sale original_sale on original_sale.id = a.original_sale_id
                left join customer original_customer on original_customer.id = original_sale.customer_id
                where ed.id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new IssuedDocumentRow(rs.getLong("id"), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"), rs.getString("status"),
                rs.getString("xml_sha256"), rs.getString("cdr_sha256"), rs.getString("xml_object_key"),
                rs.getString("provider_ticket"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("customer_name"),
                rs.getBigDecimal("total")))
            .single();
    }

    public List<DocumentEventRow> documentEvents(long id) {
        return jdbc.sql("""
                select event.previous_status, event.new_status, event.message, event.occurred_at,
                       actor.username as actor_name
                from electronic_document_event event
                left join app_user actor on actor.id = event.actor_user_id
                where event.electronic_document_id = :id
                order by event.occurred_at desc, event.id desc
                limit 50
                """)
            .param("id", id)
            .query((rs, row) -> new DocumentEventRow(rs.getString("previous_status"),
                rs.getString("new_status"), rs.getString("message"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor_name")))
            .list();
    }

    public List<FiscalActionRow> fiscalActionsForDocument(long id) {
        return jdbc.sql("""
                select far.id, far.electronic_document_id, far.action_type, far.reason, far.status,
                       far.provider_ticket, far.error_code, far.error_message, far.requested_at,
                       far.completed_at, far.cdr_sha256, ed.document_type, ed.series, ed.number
                from fiscal_action_request far
                join electronic_document ed on ed.id = far.electronic_document_id
                where far.electronic_document_id = :id
                order by far.id desc
                """)
            .param("id", id)
            .query((rs, row) -> new FiscalActionRow(rs.getLong("id"),
                rs.getLong("electronic_document_id"), rs.getString("action_type"),
                rs.getString("reason"), rs.getString("status"), rs.getString("provider_ticket"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getString("cdr_sha256"),
                rs.getString("document_type"), rs.getString("series"), rs.getLong("number")))
            .list();
    }

    public List<FiscalActionRow> fiscalActions() {
        return jdbc.sql("""
                select far.id, far.electronic_document_id, far.action_type, far.reason, far.status,
                       far.provider_ticket, far.error_code, far.error_message, far.requested_at,
                       far.completed_at, far.cdr_sha256, ed.document_type, ed.series, ed.number
                from fiscal_action_request far
                join electronic_document ed on ed.id = far.electronic_document_id
                order by far.id desc limit 100
                """)
            .query((rs, row) -> new FiscalActionRow(rs.getLong("id"),
                rs.getLong("electronic_document_id"), rs.getString("action_type"),
                rs.getString("reason"), rs.getString("status"), rs.getString("provider_ticket"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getString("cdr_sha256"),
                rs.getString("document_type"), rs.getString("series"), rs.getLong("number")))
            .list();
    }

    @Transactional
    public GenerationResult generate(long documentId, String username) {
        DocumentHeader header = headerForUpdate(documentId);
        if (!List.of("PENDING_GENERATION", "GENERATED_UNSIGNED").contains(header.status())) {
            throw new IllegalArgumentException(
                "El documento ya inició su ciclo fiscal y su XML no puede reemplazarse");
        }
        UblDocumentGenerator.DocumentData data = data(header);
        byte[] xml = generator.generate(data);
        String hash = sha256(xml);
        String relative = header.type() + "/" + header.fileName() + ".xml";
        writeAtomically(relative, xml);
        String previous = header.status();
        jdbc.sql("""
                update electronic_document set status = 'GENERATED_UNSIGNED', xml_sha256 = :hash,
                    xml_object_key = :objectKey, error_code = null, error_message = null,
                    updated_at = current_timestamp where id = :id
                """)
            .param("hash", hash).param("objectKey", relative).param("id", documentId).update();
        sales.recordDocumentEvent(documentId, previous, "GENERATED_UNSIGNED",
            "XML UBL 2.1 generado; pendiente de firma digital", username);
        audit(username, "DOCUMENT_GENERATE", documentId, "SUCCESS", hash);
        return new GenerationResult(relative, hash);
    }

    public FiscalGateway.SubmissionResult emit(long documentId, String username) {
        transactions.executeWithoutResult(status -> {
            DocumentHeader h=headerForUpdate(documentId);
            if("PENDING_GENERATION".equals(h.status()))generate(documentId,username);
        });
        return submit(documentId,username);
    }

    public FiscalGateway.SubmissionResult submit(long documentId, String username) {
        SubmissionPreparation preparation = transactions.execute(status -> prepareSubmission(documentId, username));
        if (preparation == null) throw new IllegalStateException("No se pudo preparar el envío fiscal");
        FiscalGateway.SubmissionResult result = preparation.summaryMode()
            ? gateway.submitSummary(new FiscalGateway.SummaryReference(documentId,
                preparation.summaryFileName(), preparation.summaryReference()), preparation.submissionPath())
            : gateway.submit(new FiscalGateway.DocumentReference(documentId, preparation.header().type(),
                preparation.header().series(), preparation.header().number()), preparation.submissionPath());
        String signedRelative = null;
        String signedHash = null;
        if (result.signedXml() != null) {
            signedRelative = preparation.summaryMode()
                ? "summary/signed/" + preparation.summaryReference() + ".xml"
                : "signed/" + preparation.header().fileName() + ".xml";
            writeAtomically(signedRelative, result.signedXml());
            signedHash = sha256(result.signedXml());
        }
        String cdrRelative = null;
        String cdrHash = null;
        if (result.cdrArchive() != null) {
            cdrRelative = "cdr/R-" + preparation.header().fileName() + ".zip";
            writeAtomically(cdrRelative, result.cdrArchive());
            cdrHash = sha256(result.cdrArchive());
        }
        String finalSignedRelative = signedRelative;
        String finalSignedHash = signedHash;
        String finalCdrRelative = cdrRelative;
        String finalCdrHash = cdrHash;
        transactions.executeWithoutResult(status -> finishSubmission(preparation, result,
            finalSignedRelative, finalSignedHash, finalCdrRelative, finalCdrHash, username));
        return result;
    }

    public boolean isProfileBlocked(long documentId) {
        return isLegacyProfileRejection(documentId) || jdbc.sql("""
            select count(*) from electronic_document d
            where d.id = :id and d.status = 'GENERATED_UNSIGNED'
              and (d.error_code = '0111' or d.error_code like '%:Client.0111')
              and d.cdr_object_key is null and d.cdr_sha256 is null
              and d.provider_ticket is null and d.accepted_at is null
            """).param("id", documentId).query(Long.class).single() == 1;
    }

    private boolean isLegacyProfileRejection(long documentId) {
        // Legacy 0111 records have no CDR. Never reopen an uncertain or fiscal rejection.
        return jdbc.sql("""
            select count(*) from electronic_document d
            where d.id = :id and d.status = 'REJECTED'
              and (d.error_code = '0111' or d.error_code like '%:Client.0111')
              and d.cdr_object_key is null and d.cdr_sha256 is null
              and d.provider_ticket is null and d.accepted_at is null
              and exists (select 1 from fiscal_submission_attempt a where a.electronic_document_id = d.id)
              and not exists (
                select 1 from fiscal_submission_attempt a where a.electronic_document_id = d.id
                  and (a.status <> 'REJECTED' or a.error_code is null
                    or not (a.error_code = '0111' or a.error_code like '%:Client.0111')
                    or a.response_sha256 is not null or a.provider_ticket is not null))
            """).param("id", documentId).query(Long.class).single() == 1;
    }

    private SubmissionPreparation prepareSubmission(long documentId, String username) {
        DocumentHeader header = headerForUpdate(documentId);
        boolean profileBlocked = isLegacyProfileRejection(documentId);
        if (header.objectKey() == null) {
            throw new IllegalArgumentException("Primero genera el XML UBL del comprobante");
        }
        if (List.of("ACCEPTED", "OBSERVED").contains(header.status())) {
            throw new IllegalArgumentException("El comprobante ya tiene validez fiscal y no puede reenviarse");
        }
        if (!"GENERATED_UNSIGNED".equals(header.status()) && !profileBlocked) {
            throw new IllegalArgumentException(
                "El comprobante no está listo para envío o tiene una conciliación pendiente");
        }
        boolean summaryMode = requiresDailySummary(header);
        String summaryReference = null;
        String summaryFileName = null;
        Path submissionPath = resolveStored(header.objectKey());
        if (summaryMode) {
            UblDocumentGenerator.DocumentData source = data(header);
            summaryReference = "RC-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + documentId;
            summaryFileName = source.companyRuc() + "-" + summaryReference;
            byte[] summaryXml = summaryGenerator.generate(new DailySummaryGenerator.Data(summaryReference,
                source.issueDate(), LocalDate.now(), source.companyRuc(), source.companyLegalName(),
                source.documentType(), source.series(), source.number(), source.customerDocumentType(),
                source.customerDocument(), source.subtotal(), source.taxAmount(), source.total(), source.currency(),
                source.referenceDocumentType(), source.referenceDocument(), "1"));
            String summaryUnsignedKey = "summary/unsigned/" + summaryFileName + ".xml";
            writeAtomically(summaryUnsignedKey, summaryXml);
            submissionPath = resolveStored(summaryUnsignedKey);
        }
        String requestHash = sha256(read(submissionPath));
        String idempotencyKey = documentId + ":" + requestHash;
        String attemptStatus = jdbc.sql("select status from fiscal_submission_attempt where idempotency_key = :key")
            .param("key", idempotencyKey).query(String.class).optional().orElse(null);
        if (profileBlocked && attemptStatus == null) {
            throw new IllegalArgumentException("El XML cambió desde el bloqueo de permisos; revisa el expediente antes de enviar");
        }
        if (attemptStatus == null) {
            jdbc.sql("""
                    insert into fiscal_submission_attempt (electronic_document_id, idempotency_key,
                        status, request_sha256, provider_code)
                    values (:documentId, :key, 'STARTED', :requestHash, 'SUNAT_DIRECT')
                    """).param("documentId", documentId).param("key", idempotencyKey)
                .param("requestHash", requestHash).update();
        } else if ("BLOCKED".equals(attemptStatus) || (profileBlocked && "REJECTED".equals(attemptStatus))) {
            jdbc.sql("""
                    update fiscal_submission_attempt set status = 'STARTED', error_code = null,
                        error_message = null, completed_at = null, started_at = current_timestamp
                    where idempotency_key = :key
                    """).param("key", idempotencyKey).update();
        } else {
            throw new IllegalArgumentException("Este contenido ya tuvo un intento; primero concilia su estado");
        }
        jdbc.sql("""
                update electronic_document set status = 'SUBMITTING',
                    summary_reference = coalesce(:summaryReference, summary_reference),
                    error_code = null, error_message = null, updated_at = current_timestamp
                where id = :id
                """).param("summaryReference", summaryReference).param("id", documentId).update();
        sales.recordDocumentEvent(documentId, header.status(), "SUBMITTING",
            "Intento fiscal confirmado antes de contactar a SUNAT", username);
        return new SubmissionPreparation(header, submissionPath, summaryMode, summaryReference,
            summaryFileName, requestHash, idempotencyKey);
    }

    private void finishSubmission(SubmissionPreparation preparation,
                                  FiscalGateway.SubmissionResult result,
                                  String signedRelative, String signedHash,
                                  String cdrRelative, String cdrHash, String username) {
        if (result.status() == FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED) {
            jdbc.sql("""
                    update electronic_document set status = 'GENERATED_UNSIGNED',
                        error_code = :code, error_message = :message, updated_at = current_timestamp
                    where id = :id
                    """).param("code", result.code()).param("message", result.message())
                .param("id", preparation.header().id()).update();
            jdbc.sql("""
                    update fiscal_submission_attempt set status = 'BLOCKED', error_code = :code,
                        error_message = :message, completed_at = current_timestamp
                    where idempotency_key = :key
                    """).param("code", result.code()).param("message", result.message())
                .param("key", preparation.idempotencyKey()).update();
            sales.recordDocumentEvent(preparation.header().id(), "SUBMITTING", "GENERATED_UNSIGNED",
                result.message(), username);
            audit(username, "DOCUMENT_SUBMIT", preparation.header().id(), "BLOCKED", result.code());
            return;
        }
        String nextStatus = result.status().name();
        jdbc.sql("""
                update electronic_document set status = :status, provider_ticket = :ticket,
                    error_code = :code, error_message = :message, sent_at = current_timestamp,
                    signed_xml_object_key = coalesce(:signedObjectKey, signed_xml_object_key),
                    signed_xml_sha256 = coalesce(:signedHash, signed_xml_sha256),
                    summary_reference = coalesce(:summaryReference, summary_reference),
                    summary_xml_object_key = coalesce(:summaryObjectKey, summary_xml_object_key),
                    summary_xml_sha256 = coalesce(:summaryHash, summary_xml_sha256),
                    cdr_object_key = coalesce(:cdrObjectKey, cdr_object_key),
                    cdr_sha256 = coalesce(:cdrHash, cdr_sha256),
                    accepted_at = case when :status in ('ACCEPTED', 'OBSERVED') then current_timestamp else accepted_at end,
                    updated_at = current_timestamp where id = :id
                """)
            .param("status", nextStatus).param("ticket", result.ticket()).param("code", result.code())
            .param("message", result.message())
            .param("signedObjectKey", preparation.summaryMode() ? null : signedRelative)
            .param("signedHash", preparation.summaryMode() ? null : signedHash)
            .param("summaryReference", preparation.summaryReference())
            .param("summaryObjectKey", preparation.summaryMode() ? signedRelative : null)
            .param("summaryHash", preparation.summaryMode() ? signedHash : null)
            .param("cdrObjectKey", cdrRelative)
            .param("cdrHash", cdrHash).param("id", preparation.header().id()).update();
        jdbc.sql("""
                update fiscal_submission_attempt set status = :status, response_sha256 = :responseHash,
                    provider_ticket = :ticket, error_code = :code, error_message = :message,
                    completed_at = current_timestamp where idempotency_key = :key
                """)
            .param("key", preparation.idempotencyKey()).param("status", nextStatus)
            .param("responseHash", cdrHash).param("ticket", result.ticket())
            .param("code", result.code()).param("message", result.message()).update();
        sales.recordDocumentEvent(preparation.header().id(), "SUBMITTING", nextStatus,
            result.message(), username);
        audit(username, "DOCUMENT_SUBMIT", preparation.header().id(), "SUCCESS", nextStatus);
    }

    @Transactional
    public CdrResult registerCdr(long documentId, MultipartFile upload, String username) {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("Selecciona el CDR devuelto por SUNAT o el PSE");
        }
        byte[] original;
        try {
            original = upload.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("No se pudo leer el archivo CDR", exception);
        }
        if (original.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("El CDR supera el límite de 5 MB");
        }
        String originalName = upload.getOriginalFilename() == null ? "cdr.xml" : upload.getOriginalFilename();
        boolean zip = originalName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
            || cdrReader.looksLikeZip(original);
        DocumentHeader header = headerForUpdate(documentId);
        if (!List.of("GENERATED_UNSIGNED", "REJECTED").contains(header.status())
            || jdbc.sql("""
                select count(*) from electronic_document where id = :id
                  and (cdr_object_key is not null or cdr_sha256 is not null or accepted_at is not null)
                """).param("id", documentId).query(Long.class).single() != 0) {
            throw new IllegalArgumentException(
                "No se puede reemplazar un CDR archivado ni modificar un expediente en proceso o con respuesta pendiente");
        }
        CdrReader.Response response = cdrReader.read(original, zip, header.series() + "-" + header.number());
        String extension = zip ? ".zip" : ".xml";
        String relative = "cdr/" + header.fileName() + extension;
        writeAtomically(relative, original);
        String hash = sha256(original);
        String nextStatus = "CDR_RECORDED_UNVERIFIED";
        jdbc.sql("""
                update electronic_document set status = :status, cdr_object_key = :objectKey,
                    cdr_sha256 = :hash, error_code = :code, error_message = :message,
                    updated_at = current_timestamp where id = :id
                """)
            .param("status", nextStatus).param("objectKey", relative).param("hash", hash)
            .param("code", response.code()).param("message", response.description())
            .param("id", documentId).update();
        sales.recordDocumentEvent(documentId, header.status(), nextStatus,
            "CDR manual preservado sin afirmar autenticidad: " + response.description(), username);
        audit(username, "CDR_REGISTER", documentId, "UNVERIFIED", response.code() + ":" + hash);
        return new CdrResult(nextStatus, response.code(), response.description(), hash);
    }

    @Transactional
    public String requestCancellation(long documentId, String reason, String username) {
        if (reason == null || reason.isBlank() || reason.strip().length() < 5) {
            throw new IllegalArgumentException("Explica el motivo de anulación con al menos 5 caracteres");
        }
        DocumentHeader header = headerForUpdate(documentId);
        if (List.of("CANCELLED", "CANCELLED_LOCAL", "VOID_REQUESTED").contains(header.status())) {
            throw new IllegalArgumentException("El documento ya tiene una anulación registrada");
        }
        if (List.of("SUBMISSION_UNKNOWN", "PROCESSING", "CDR_RECORDED_UNVERIFIED",
            "SUBMITTING", "VOID_SUBMITTING", "VOID_PROCESSING",
            "VOID_SUBMISSION_UNKNOWN").contains(header.status())) {
            throw new IllegalArgumentException("Primero concilia el estado fiscal antes de solicitar una anulación");
        }
        if (List.of("ACCEPTED", "OBSERVED").contains(header.status())) {
            String actionType = requiresDailySummary(header) ? "SUMMARY_VOID" : "VOID";
            jdbc.sql("""
                    insert into fiscal_action_request (electronic_document_id, action_type, reason,
                        status, requested_by)
                    values (:documentId, :actionType, :reason, 'PENDING', :userId)
                    """)
                .param("documentId", documentId).param("actionType", actionType)
                .param("reason", reason.strip())
                .param("userId", catalog.userId(username)).update();
            jdbc.sql("update electronic_document set status = 'VOID_REQUESTED', updated_at = current_timestamp where id = :id")
                .param("id", documentId).update();
            sales.recordDocumentEvent(documentId, header.status(), "VOID_REQUESTED",
                "Solicitud de baja pendiente de envío fiscal: " + reason.strip(), username);
            audit(username, "DOCUMENT_VOID_REQUEST", documentId, "SUCCESS", reason.strip());
            return "Solicitud de baja creada; aún debe ser aceptada por SUNAT/PSE";
        }
        jdbc.sql("update electronic_document set status = 'CANCELLED_LOCAL', updated_at = current_timestamp where id = :id")
            .param("id", documentId).update();
        if (header.saleId() != null) {
            restoreSaleStock(header.saleId(), documentId, username);
            jdbc.sql("update sale set status = 'CANCELLED', updated_at = current_timestamp where id = :id")
                .param("id", header.saleId()).update();
        } else if (header.adjustmentId() != null) {
            jdbc.sql("update adjustment_document set status = 'CANCELLED' where id = :id")
                .param("id", header.adjustmentId()).update();
        }
        sales.recordDocumentEvent(documentId, header.status(), "CANCELLED_LOCAL",
            "Documento no enviado anulado localmente: " + reason.strip(), username);
        audit(username, "DOCUMENT_CANCEL_LOCAL", documentId, "SUCCESS", reason.strip());
        return "Documento no enviado anulado y stock restituido cuando correspondía";
    }

    public FiscalGateway.SubmissionResult submitCancellation(long actionId, String username) {
        VoidPreparation preparation = transactions.execute(status -> prepareVoid(actionId, username));
        if (preparation == null) throw new IllegalStateException("No se pudo preparar la baja electrónica");
        VoidAction action = preparation.action();
        var result = gateway.submitSummary(
            new FiscalGateway.SummaryReference(action.id(), preparation.fileName(), preparation.identifier()),
            resolveStored(preparation.unsignedKey()));
        String signedKey = null;
        String signedHash = null;
        if (result.signedXml() != null) {
            signedKey = "void/signed/" + preparation.fileName() + ".xml";
            writeAtomically(signedKey, result.signedXml());
            signedHash = sha256(result.signedXml());
        }
        String finalSignedKey = signedKey;
        String finalSignedHash = signedHash;
        transactions.executeWithoutResult(status -> finishVoid(preparation, result,
            finalSignedKey, finalSignedHash, username));
        return result;
    }

    private VoidPreparation prepareVoid(long actionId, String username) {
        VoidAction action = voidAction(actionId, true);
        if (!"PENDING".equals(action.status())) {
            throw new IllegalArgumentException("La solicitud de baja ya inició su procesamiento");
        }
        boolean summaryVoid = "SUMMARY_VOID".equals(action.actionType());
        String identifier = (summaryVoid ? "RC-" : "RA-")
            + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + action.id();
        String fileName = action.ruc() + "-" + identifier;
        byte[] unsigned;
        if (summaryVoid) {
            UblDocumentGenerator.DocumentData source = data(header(action.documentId()));
            unsigned = summaryGenerator.generate(new DailySummaryGenerator.Data(identifier,
                action.referenceDate(), LocalDate.now(), action.ruc(), action.legalName(),
                action.documentType(), action.series(), action.number(), source.customerDocumentType(),
                source.customerDocument(), source.subtotal(), source.taxAmount(), source.total(), source.currency(),
                source.referenceDocumentType(), source.referenceDocument(), "3"));
        } else {
            unsigned = voidGenerator.generate(new VoidedDocumentGenerator.Data(identifier,
                action.referenceDate(), LocalDate.now(), action.ruc(), action.legalName(),
                action.documentType(), action.series(), action.number(), action.reason()));
        }
        String unsignedKey = "void/unsigned/" + fileName + ".xml";
        writeAtomically(unsignedKey, unsigned);
        jdbc.sql("""
                update fiscal_action_request set status = 'SUBMITTING', file_name = :fileName,
                    xml_object_key = :xmlKey, xml_sha256 = :xmlHash,
                    error_code = null, error_message = null
                where id = :id
                """).param("fileName", fileName).param("xmlKey", unsignedKey)
            .param("xmlHash", sha256(unsigned)).param("id", actionId).update();
        jdbc.sql("update electronic_document set status = 'VOID_SUBMITTING', updated_at = current_timestamp where id = :id")
            .param("id", action.documentId()).update();
        sales.recordDocumentEvent(action.documentId(), "VOID_REQUESTED", "VOID_SUBMITTING",
            "Intento de baja confirmado antes de contactar a SUNAT", username);
        return new VoidPreparation(action, identifier, fileName, unsignedKey);
    }

    private void finishVoid(VoidPreparation preparation, FiscalGateway.SubmissionResult result,
                            String signedKey, String signedHash, String username) {
        VoidAction action = preparation.action();
        if (result.status() == FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED) {
            jdbc.sql("""
                    update fiscal_action_request set status = 'PENDING', error_code = :code,
                        error_message = :message
                    where id = :id
                    """).param("code", result.code()).param("message", result.message())
                .param("id", action.id()).update();
            jdbc.sql("update electronic_document set status = 'VOID_REQUESTED', updated_at = current_timestamp where id = :id")
                .param("id", action.documentId()).update();
            sales.recordDocumentEvent(action.documentId(), "VOID_SUBMITTING", "VOID_REQUESTED",
                result.message(), username);
            audit(username, "VOID_SUBMIT", action.documentId(), "BLOCKED", result.code());
            return;
        }
        String actionStatus = result.status().name();
        jdbc.sql("""
                update fiscal_action_request set status = :status,
                    signed_xml_object_key = :signedKey, signed_xml_sha256 = :signedHash,
                    provider_ticket = :ticket, error_code = :code, error_message = :message,
                    submitted_at = current_timestamp
                where id = :id
                """)
            .param("status", actionStatus)
            .param("signedKey", signedKey).param("signedHash", signedHash)
            .param("ticket", result.ticket()).param("code", result.code())
            .param("message", result.message()).param("id", action.id()).update();
        String documentStatus = switch (result.status()) {
            case PROCESSING -> "VOID_PROCESSING";
            case SUBMISSION_UNKNOWN -> "VOID_SUBMISSION_UNKNOWN";
            case REJECTED -> "ACCEPTED";
            default -> "VOID_REQUESTED";
        };
        jdbc.sql("update electronic_document set status = :status, updated_at = current_timestamp where id = :id")
            .param("status", documentStatus).param("id", action.documentId()).update();
        sales.recordDocumentEvent(action.documentId(), "VOID_SUBMITTING", documentStatus,
            result.message(), username);
        audit(username, "VOID_SUBMIT", action.documentId(), actionStatus, result.code());
    }

    @Transactional
    public FiscalGateway.SubmissionResult pollCancellation(long actionId, String username) {
        VoidAction action = voidAction(actionId, true);
        if (!List.of("PROCESSING", "SUBMISSION_UNKNOWN").contains(action.status())
            || action.ticket() == null || action.ticket().isBlank()) {
            throw new IllegalArgumentException("La solicitud no tiene un ticket SUNAT pendiente de consulta");
        }
        DocumentHeader document = headerForUpdate(action.documentId());
        if (!List.of("VOID_PROCESSING", "VOID_SUBMISSION_UNKNOWN").contains(document.status())) {
            throw new IllegalArgumentException("El comprobante ya no tiene esta baja pendiente de consulta");
        }
        String identifier = action.fileName().substring(action.ruc().length() + 1);
        var result = gateway.poll(action.ticket(), identifier);
        if (result.status() == FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED) {
            return result;
        }
        String cdrKey = null;
        String cdrHash = null;
        if (result.cdrArchive() != null) {
            cdrKey = "void/cdr/R-" + action.fileName() + ".zip";
            writeAtomically(cdrKey, result.cdrArchive());
            cdrHash = sha256(result.cdrArchive());
        }
        jdbc.sql("""
                update fiscal_action_request set status = :status, error_code = :code,
                    error_message = :message, cdr_object_key = coalesce(:cdrKey, cdr_object_key),
                    cdr_sha256 = coalesce(:cdrHash, cdr_sha256),
                    completed_at = case when :finished then current_timestamp else completed_at end
                where id = :id
                """)
            .param("status", result.status().name()).param("code", result.code())
            .param("message", result.message()).param("cdrKey", cdrKey).param("cdrHash", cdrHash)
            .param("finished", List.of(FiscalGateway.SubmissionResult.Status.ACCEPTED,
                FiscalGateway.SubmissionResult.Status.OBSERVED,
                FiscalGateway.SubmissionResult.Status.REJECTED).contains(result.status()))
            .param("id", actionId).update();
        if (List.of(FiscalGateway.SubmissionResult.Status.ACCEPTED,
            FiscalGateway.SubmissionResult.Status.OBSERVED).contains(result.status())) {
            completeVoid(action, username);
        } else if (result.status() == FiscalGateway.SubmissionResult.Status.REJECTED) {
            jdbc.sql("update electronic_document set status = 'ACCEPTED', updated_at = current_timestamp where id = :id")
                .param("id", action.documentId()).update();
            sales.recordDocumentEvent(action.documentId(), "VOID_PROCESSING", "ACCEPTED",
                "SUNAT rechazó la baja; el comprobante sigue vigente: " + result.message(), username);
        }
        audit(username, "VOID_POLL", action.documentId(), result.status().name(), result.code());
        return result;
    }

    @Transactional
    public FiscalGateway.SubmissionResult reconcile(long documentId, String username) {
        DocumentHeader header = headerForUpdate(documentId);
        if (!List.of("SUBMITTING", "SUBMISSION_UNKNOWN", "PROCESSING", "CDR_RECORDED_UNVERIFIED").contains(header.status())) {
            throw new IllegalArgumentException("El comprobante no tiene una respuesta fiscal pendiente");
        }
        FiscalGateway.SubmissionResult result;
        if (header.summaryReference() != null) {
            if (header.providerTicket() == null) {
                throw new IllegalArgumentException("El resumen quedó sin ticket y requiere revisión manual");
            }
            result = gateway.poll(header.providerTicket(), header.summaryReference());
        } else {
            result = gateway.reconcile(new FiscalGateway.DocumentReference(documentId,
                header.type(), header.series(), header.number()));
        }
        if (result.status() == FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED) {
            return result;
        }
        if (result.cdrArchive() != null) {
            String key = "cdr/R-" + header.fileName() + ".zip";
            writeAtomically(key, result.cdrArchive());
            jdbc.sql("""
                    update electronic_document set cdr_object_key = :key, cdr_sha256 = :hash
                    where id = :id
                    """).param("key", key).param("hash", sha256(result.cdrArchive()))
                .param("id", documentId).update();
        }
        String next = result.status() == FiscalGateway.SubmissionResult.Status.PROCESSING
            ? header.status() : result.status().name();
        jdbc.sql("""
                update electronic_document set status = :status, error_code = :code,
                    error_message = :message,
                    accepted_at = case when :status in ('ACCEPTED', 'OBSERVED') then current_timestamp else accepted_at end,
                    updated_at = current_timestamp where id = :id
                """).param("status", next).param("code", result.code())
            .param("message", result.message()).param("id", documentId).update();
        sales.recordDocumentEvent(documentId, header.status(), next,
            "Conciliación SUNAT: " + result.message(), username);
        audit(username, "DOCUMENT_RECONCILE", documentId, result.status().name(), result.code());
        return result;
    }

    public Resource xmlResource(long documentId) {
        DocumentHeader header = header(documentId);
        String selectedKey = header.signedObjectKey() == null ? header.objectKey() : header.signedObjectKey();
        if (selectedKey == null) {
            throw new IllegalArgumentException("El comprobante todavía no tiene XML");
        }
        Path path = resolveStored(selectedKey);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("El archivo XML no está disponible en el almacenamiento");
        }
        return new FileSystemResource(path);
    }

    public String downloadName(long documentId) {
        return header(documentId).fileName() + ".xml";
    }

    public Resource cdrResource(long documentId) {
        DocumentHeader header = header(documentId);
        String key = jdbc.sql("select cdr_object_key from electronic_document where id = :id")
            .param("id", documentId).query(String.class).optional().orElse(null);
        if (key == null) {
            throw new IllegalArgumentException("El comprobante todavía no tiene CDR almacenado");
        }
        Path path = resolveStored(key);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("El CDR no está disponible en el almacenamiento");
        }
        return new FileSystemResource(path);
    }

    public String cdrDownloadName(long documentId) {
        DocumentHeader header = header(documentId);
        String key = jdbc.sql("select cdr_object_key from electronic_document where id = :id")
            .param("id", documentId).query(String.class).optional().orElse(null);
        String extension = key != null && key.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")
            ? ".xml" : ".zip";
        return "R-" + header.fileName() + extension;
    }

    public List<String> cdrObservations(long documentId) {
        String key = jdbc.sql("select cdr_object_key from electronic_document where id = :id")
            .param("id", documentId).query(String.class).optional().orElse(null);
        if (key == null) return List.of();
        DocumentHeader header = header(documentId);
        try (var stream = Files.newInputStream(resolveStored(key))) {
            byte[] bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
            String reference = header.summaryReference() != null
                ? header.summaryReference() : header.series() + "-" + header.number();
            return cdrReader.read(bytes, cdrReader.looksLikeZip(bytes), reference).observations();
        } catch (IOException | IllegalArgumentException exception) {
            return List.of("No se pudieron leer las observaciones del CDR archivado. Revisa su integridad antes de continuar.");
        }
    }

    public byte[] printXml(long id) {
        DocumentHeader h=header(id);
        String key=jdbc.sql("select xml_object_key from electronic_document where id=:id").param("id",id).query(String.class).optional().orElse(null);
        // Existing XML is the document snapshot; do not reconstruct it from mutable masters.
        return key==null?generator.generate(data(h)):read(resolveStored(key));
    }

    private UblDocumentGenerator.DocumentData data(DocumentHeader header) {
        Company company = jdbc.sql("""
                select ruc, legal_name, trade_name, fiscal_address, ubigeo
                from company_profile where active = true order by id limit 1
                """)
            .query((rs, row) -> new Company(rs.getString("ruc"), rs.getString("legal_name"),
                rs.getString("trade_name"), rs.getString("fiscal_address"), rs.getString("ubigeo")))
            .single();
        if (header.saleId() != null) {
            SaleFiscal fiscal = saleFiscal(header.saleId());
            var lines = jdbc.sql("""
                    select sl.line_number, coalesce(p.sku, 'SERVICIO') as sku, sl.description,
                           sl.unit_code, sl.quantity, sl.unit_value, sl.tax_amount, sl.line_total,
                           sl.igv_affectation_code, sl.tax_rate, sl.sunat_product_code
                    from sale_line sl left join product p on p.id = sl.product_id
                    where sl.sale_id = :id order by sl.line_number
                    """)
                .param("id", header.saleId())
                .query((rs, row) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal unitValue = rs.getBigDecimal("unit_value");
                    return new UblDocumentGenerator.LineData(rs.getInt("line_number"), rs.getString("sku"),
                        rs.getString("description"), rs.getString("unit_code"), quantity, unitValue,
                        unitValue.multiply(quantity).setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("tax_amount"), rs.getBigDecimal("line_total"),
                        rs.getString("igv_affectation_code"), rs.getBigDecimal("tax_rate"),
                        rs.getString("sunat_product_code"));
                }).list();
            return documentData(header, company, fiscal, null, null, lines);
        }
        AdjustmentFiscal adjustment = jdbc.sql("""
                select a.issue_date, a.subtotal, a.tax_amount, a.total, a.reason_code, a.reason,
                       s.document_type as reference_type, s.series as reference_series,
                       s.number as reference_number, c.document_type as customer_type,
                       c.document_number as customer_document, c.legal_name as customer_name
                from adjustment_document a
                join sale s on s.id = a.original_sale_id
                join customer c on c.id = s.customer_id
                where a.id = :id
                """)
            .param("id", header.adjustmentId())
            .query((rs, row) -> new AdjustmentFiscal(rs.getObject("issue_date", LocalDate.class),
                rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total"),
                rs.getString("reason_code"), rs.getString("reason"), rs.getString("reference_type"),
                rs.getString("reference_series") + "-" + rs.getLong("reference_number"),
                rs.getString("customer_type"), rs.getString("customer_document"),
                rs.getString("customer_name")))
            .single();
        SaleFiscal fiscal = new SaleFiscal(adjustment.issueDate(), "PEN", adjustment.subtotal(),
            adjustment.tax(), adjustment.total(), adjustment.customerType(), adjustment.customerDocument(),
            adjustment.customerName(), "0101", "CONTADO", null, null, null, null, null, null, null);
        var line = new UblDocumentGenerator.LineData(1, "AJUSTE", adjustment.reason(), "NIU",
            BigDecimal.ONE, adjustment.subtotal(), adjustment.subtotal(), adjustment.tax(), adjustment.total());
        return documentData(header, company, fiscal, adjustment.referenceType(), adjustment.referenceLabel(),
            List.of(line), adjustment.reasonCode(), adjustment.reason());
    }

    private UblDocumentGenerator.DocumentData documentData(DocumentHeader header, Company company,
                                                            SaleFiscal fiscal, String referenceType,
                                                            String referenceLabel,
                                                            List<UblDocumentGenerator.LineData> lines) {
        return documentData(header, company, fiscal, referenceType, referenceLabel, lines, null, null);
    }

    private UblDocumentGenerator.DocumentData documentData(DocumentHeader header, Company company,
                                                            SaleFiscal fiscal, String referenceType,
                                                            String referenceLabel,
                                                            List<UblDocumentGenerator.LineData> lines,
                                                            String reasonCode, String reason) {
        return new UblDocumentGenerator.DocumentData(header.type(), header.series(), header.number(),
            fiscal.issueDate(), fiscal.currency(), company.ruc(), company.legalName(), company.tradeName(),
            company.address(), company.ubigeo(), fiscal.customerType(), fiscal.customerDocument(),
            fiscal.customerName(), fiscal.subtotal(), fiscal.tax(), fiscal.total(), referenceType,
            referenceLabel, reasonCode, reason, lines, fiscal.operationType(), fiscal.paymentCondition(),
            fiscal.dueDate(), fiscal.purchaseOrderReference(), fiscal.relatedGuideReference(),
            fiscal.customerAddress(), fiscal.customerUbigeo(), fiscal.deliveryAddress(), fiscal.deliveryUbigeo());
    }

    private SaleFiscal saleFiscal(long saleId) {
        return jdbc.sql("""
                select s.issue_date, s.currency_code, s.subtotal, s.tax_amount, s.total,
                       s.operation_type, s.payment_condition, s.due_date, s.purchase_order_reference,
                       s.related_guide_reference, s.delivery_address, s.delivery_ubigeo,
                       c.document_type, c.document_number, c.legal_name, c.address, c.ubigeo
                from sale s join customer c on c.id = s.customer_id where s.id = :id
                """)
            .param("id", saleId)
            .query((rs, row) -> new SaleFiscal(rs.getObject("issue_date", LocalDate.class),
                rs.getString("currency_code"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getString("operation_type"), rs.getString("payment_condition"),
                rs.getObject("due_date", LocalDate.class), rs.getString("purchase_order_reference"),
                rs.getString("related_guide_reference"), rs.getString("address"), rs.getString("ubigeo"),
                rs.getString("delivery_address"), rs.getString("delivery_ubigeo")))
            .single();
    }

    private DocumentHeader header(long id) {
        return jdbc.sql("""
                select id, sale_id, adjustment_id, document_type, series, number, status,
                       xml_object_key, signed_xml_object_key, provider_ticket, summary_reference
                from electronic_document where id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new DocumentHeader(rs.getLong("id"), nullableLong(rs.getObject("sale_id")),
                nullableLong(rs.getObject("adjustment_id")), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"), rs.getString("status"),
                rs.getString("xml_object_key"), rs.getString("signed_xml_object_key"),
                rs.getString("provider_ticket"), rs.getString("summary_reference")))
            .single();
    }

    private DocumentHeader headerForUpdate(long id) {
        return jdbc.sql("""
                select id, sale_id, adjustment_id, document_type, series, number, status,
                       xml_object_key, signed_xml_object_key, provider_ticket, summary_reference
                from electronic_document where id = :id for update
                """)
            .param("id", id)
            .query((rs, row) -> new DocumentHeader(rs.getLong("id"), nullableLong(rs.getObject("sale_id")),
                nullableLong(rs.getObject("adjustment_id")), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"), rs.getString("status"),
                rs.getString("xml_object_key"), rs.getString("signed_xml_object_key"),
                rs.getString("provider_ticket"), rs.getString("summary_reference")))
            .single();
    }

    private boolean requiresDailySummary(DocumentHeader header) {
        return "03".equals(header.type())
            || (List.of("07", "08").contains(header.type()) && header.series().startsWith("B"));
    }

    private VoidAction voidAction(long actionId, boolean lock) {
        if (lock) {
            jdbc.sql("select id from fiscal_action_request where id = :id for update")
                .param("id", actionId).query(Long.class).single();
        }
        return jdbc.sql("""
                select far.id, far.electronic_document_id, far.action_type, far.status, far.reason,
                       far.provider_ticket, far.file_name, ed.sale_id, ed.adjustment_id,
                       ed.document_type, ed.series, ed.number,
                       coalesce(s.issue_date, a.issue_date) as reference_date,
                       cp.ruc, cp.legal_name
                from fiscal_action_request far
                join electronic_document ed on ed.id = far.electronic_document_id
                left join sale s on s.id = ed.sale_id
                left join adjustment_document a on a.id = ed.adjustment_id
                join company_profile cp on cp.active = true
                where far.id = :id
                """)
            .param("id", actionId)
            .query((rs, row) -> new VoidAction(rs.getLong("id"),
                rs.getLong("electronic_document_id"), rs.getString("action_type"), rs.getString("status"),
                rs.getString("reason"), rs.getString("provider_ticket"),
                rs.getString("file_name"), nullableLong(rs.getObject("sale_id")),
                nullableLong(rs.getObject("adjustment_id")), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"),
                rs.getObject("reference_date", LocalDate.class), rs.getString("ruc"),
                rs.getString("legal_name")))
            .single();
    }

    private void completeVoid(VoidAction action, String username) {
        jdbc.sql("update electronic_document set status = 'CANCELLED', updated_at = current_timestamp where id = :id")
            .param("id", action.documentId()).update();
        if (action.saleId() != null) {
            restoreSaleStock(action.saleId(), action.documentId(), username);
            jdbc.sql("update sale set status = 'CANCELLED', updated_at = current_timestamp where id = :id")
                .param("id", action.saleId()).update();
        } else if (action.adjustmentId() != null) {
            jdbc.sql("update adjustment_document set status = 'CANCELLED' where id = :id")
                .param("id", action.adjustmentId()).update();
        }
        sales.recordDocumentEvent(action.documentId(), "VOID_PROCESSING", "CANCELLED",
            "SUNAT aceptó la comunicación de baja", username);
    }

    private void restoreSaleStock(long saleId, long documentId, String username) {
        var dispatches=jdbc.sql("select id,order_id,status from commercial_dispatch where sale_id=:sale")
            .param("sale",saleId).query().listOfRows();
        if(!dispatches.isEmpty()) {
            var dispatch=dispatches.getFirst();
            long orderId=((Number)dispatch.get("order_id")).longValue();
            jdbc.sql("select id from commercial_order where id=:id for update").param("id",orderId).query(Long.class).single();
            var current=jdbc.sql("select id,status from commercial_dispatch where id=:id for update")
                .param("id",dispatch.get("id")).query().singleRow();
            if("PREPARED".equals(current.get("status"))) {
                var allocated=jdbc.sql("select order_line_id,quantity from commercial_dispatch_line where dispatch_id=:id")
                    .param("id",current.get("id")).query().listOfRows();
                for(var line:allocated)jdbc.sql("update commercial_order_line set dispatched_quantity=dispatched_quantity-:qty where id=:id")
                    .param("qty",line.get("quantity")).param("id",line.get("order_line_id")).update();
                jdbc.sql("update commercial_dispatch set status='CANCELLED' where id=:id").param("id",current.get("id")).update();
                jdbc.sql("""
                    update commercial_order set status=case
                    when exists(select 1 from commercial_order_line l where l.order_id=:id and l.dispatched_quantity>0) then 'PARTIAL'
                    else 'CONFIRMED' end where id=:id
                    """).param("id",orderId).update();
            }
            // Fiscal cancellation is not evidence of a physical return. Prepared orders never moved stock.
            return;
        }
        var lines = jdbc.sql("""
                select sl.product_id, sl.quantity from sale_line sl
                join product p on p.id = sl.product_id
                where sl.sale_id = :saleId and p.stock_controlled = true
                """)
            .param("saleId", saleId)
            .query((rs, row) -> new StockReturn(rs.getLong("product_id"), rs.getBigDecimal("quantity")))
            .list();
        for (StockReturn line : lines) {
            catalog.insertMovement(line.productId(), line.quantity(), "SALE_CANCEL_IN", "ELECTRONIC_DOCUMENT",
                String.valueOf(documentId), null, null, "Reposición por anulación local", username);
        }
    }

    private void writeAtomically(String relative, byte[] content) {
        Path destination = resolveStored(relative);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".ubl-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo guardar el XML en el almacenamiento seguro", exception);
        }
    }

    private Path resolveStored(String relative) {
        Path result = storageRoot.resolve(relative).normalize();
        if (!result.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Ruta de documento no permitida");
        }
        return result;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer el XML almacenado", exception);
        }
    }

    private void audit(String username, String action, long documentId, String outcome, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:actor, :action, 'ELECTRONIC_DOCUMENT', :resourceId,
                    :outcome, :correlationId, :details)
                """)
            .param("actor", catalog.userId(username)).param("action", action)
            .param("resourceId", String.valueOf(documentId)).param("outcome", outcome)
            .param("correlationId", java.util.UUID.randomUUID().toString()).param("details", details).update();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record DocumentHeader(long id, Long saleId, Long adjustmentId, String type,
                                  String series, long number, String status, String objectKey,
                                  String signedObjectKey, String providerTicket,
                                  String summaryReference) {
        String fileName() { return type + "-" + series + "-" + number; }
    }
    private record SubmissionPreparation(DocumentHeader header, Path submissionPath,
                                         boolean summaryMode, String summaryReference,
                                         String summaryFileName, String requestHash,
                                         String idempotencyKey) { }
    private record Company(String ruc, String legalName, String tradeName, String address, String ubigeo) { }
    private record SaleFiscal(LocalDate issueDate, String currency, BigDecimal subtotal, BigDecimal tax,
                              BigDecimal total, String customerType, String customerDocument,
                              String customerName, String operationType, String paymentCondition,
                              LocalDate dueDate, String purchaseOrderReference, String relatedGuideReference,
                              String customerAddress, String customerUbigeo, String deliveryAddress,
                              String deliveryUbigeo) { }
    private record AdjustmentFiscal(LocalDate issueDate, BigDecimal subtotal, BigDecimal tax,
                                    BigDecimal total, String reasonCode, String reason,
                                    String referenceType, String referenceLabel, String customerType,
                                    String customerDocument, String customerName) { }
    private record StockReturn(long productId, BigDecimal quantity) { }
    private record VoidAction(long id, long documentId, String actionType, String status, String reason,
                              String ticket, String fileName, Long saleId, Long adjustmentId,
                              String documentType, String series, long number,
                              LocalDate referenceDate, String ruc, String legalName) { }
    private record VoidPreparation(VoidAction action, String identifier,
                                   String fileName, String unsignedKey) { }
    public record IssuedDocumentRow(long id, String type, String series, long number, String status,
                                    String sha256, String cdrSha256, String objectKey, String ticket, String errorCode,
                                    String errorMessage, OffsetDateTime sentAt, OffsetDateTime acceptedAt,
                                    OffsetDateTime createdAt, String customerName, BigDecimal total) {
        public String label() { return series + "-" + String.format("%08d", number); }
    }
    public record GenerationResult(String objectKey, String sha256) { }
    public record CdrResult(String status, String code, String description, String sha256) { }
    public record DocumentEventRow(String previousStatus, String newStatus, String message,
                                   OffsetDateTime occurredAt, String actorName) { }
    public record FiscalActionRow(long id, long documentId, String actionType, String reason,
                                  String status, String ticket, String errorCode,
                                  String errorMessage, OffsetDateTime requestedAt,
                                  OffsetDateTime completedAt, String cdrSha256,
                                  String documentType, String series, long number) {
        public String documentLabel() { return series + "-" + String.format("%08d", number); }
    }
}
