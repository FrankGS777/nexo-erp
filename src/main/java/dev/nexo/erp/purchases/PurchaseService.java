package dev.nexo.erp.purchases;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.catalog.CatalogService;

@Service
public class PurchaseService {

    private static final BigDecimal IGV_FACTOR = new BigDecimal("1.18");
    private final JdbcClient jdbc;
    private final CatalogService catalog;

    public PurchaseService(JdbcClient jdbc, CatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public List<PurchaseRow> purchases() {
        return jdbc.sql("""
                select p.id, p.issue_date, p.supplier_document_type, p.supplier_series,
                       p.supplier_number, p.currency_code, p.total, p.status, p.inventory_status,
                       s.document_number as supplier_document, s.legal_name as supplier_name,
                       u.display_name as created_by, p.created_at
                from purchase p
                join supplier s on s.id = p.supplier_id
                join app_user u on u.id = p.created_by
                order by p.issue_date desc, p.id desc
                limit 200
                """)
            .query((rs, row) -> new PurchaseRow(
                rs.getLong("id"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("supplier_document_type"), rs.getString("supplier_series"),
                rs.getString("supplier_number"), rs.getString("currency_code"),
                rs.getBigDecimal("total"), rs.getString("status"), rs.getString("inventory_status"),
                rs.getString("supplier_document"), rs.getString("supplier_name"),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class)))
            .list();
    }

    public PurchaseDetail detail(long id) {
        PurchaseHeader header = jdbc.sql("""
                select p.id, p.issue_date, p.supplier_document_type, p.supplier_series,
                       p.supplier_number, p.currency_code, p.subtotal, p.tax_amount, p.total,
                       p.status, p.inventory_status, p.inventory_received_at, p.notes,
                       p.created_at, p.updated_at, p.cancelled_at,
                       p.cancellation_reason, s.document_type as supplier_document_type_code,
                       s.document_number as supplier_document, s.legal_name as supplier_name,
                       s.email as supplier_email, s.phone as supplier_phone, s.address as supplier_address,
                       u.display_name as created_by, cu.display_name as cancelled_by,
                       ru.display_name as inventory_received_by
                from purchase p
                join supplier s on s.id = p.supplier_id
                join app_user u on u.id = p.created_by
                left join app_user cu on cu.id = p.cancelled_by
                left join app_user ru on ru.id = p.inventory_received_by
                where p.id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new PurchaseHeader(
                rs.getLong("id"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("supplier_document_type"), rs.getString("supplier_series"),
                rs.getString("supplier_number"), rs.getString("currency_code"),
                rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total"), rs.getString("status"), rs.getString("inventory_status"),
                rs.getObject("inventory_received_at", OffsetDateTime.class), rs.getString("notes"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("cancelled_at", OffsetDateTime.class), rs.getString("cancellation_reason"),
                rs.getString("supplier_document_type_code"), rs.getString("supplier_document"),
                rs.getString("supplier_name"), rs.getString("supplier_email"),
                rs.getString("supplier_phone"), rs.getString("supplier_address"),
                rs.getString("created_by"), rs.getString("cancelled_by"),
                rs.getString("inventory_received_by")))
            .optional().orElseThrow(() -> new IllegalArgumentException("La compra no existe"));

        List<PurchaseLine> lines = jdbc.sql("""
                select pl.line_number, p.sku, pl.description, pl.quantity, pl.unit_cost,
                       pl.tax_amount, pl.line_total, pl.lot_number, pl.expiration_date
                from purchase_line pl join product p on p.id = pl.product_id
                where pl.purchase_id = :id order by pl.line_number
                """)
            .param("id", id)
            .query((rs, row) -> new PurchaseLine(rs.getInt("line_number"), rs.getString("sku"),
                rs.getString("description"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("line_total"), rs.getString("lot_number"),
                rs.getObject("expiration_date", LocalDate.class)))
            .list();
        return new PurchaseDetail(header, lines);
    }

    @Transactional
    public long create(
        long supplierId,
        String documentType,
        String series,
        String number,
        LocalDate issueDate,
        List<Long> productIds,
        List<BigDecimal> quantities,
        List<BigDecimal> unitCosts,
        List<String> lots,
        List<LocalDate> expirations,
        String notes,
        String username
    ) {
        validateLines(productIds, quantities, unitCosts);
        if (!List.of("01", "03", "07", "08", "00").contains(documentType)) {
            throw new IllegalArgumentException("Tipo de comprobante de compra no permitido");
        }
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("El número del comprobante del proveedor es obligatorio");
        }
        if (issueDate != null && issueDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("La fecha de emisión no puede estar en el futuro");
        }
        if (series != null && !series.isBlank() && !series.strip().matches("[A-Za-z0-9-]{1,20}")) {
            throw new IllegalArgumentException("La serie contiene caracteres no permitidos");
        }
        jdbc.sql("select id from supplier where id = :id and active = true")
            .param("id", supplierId).query(Long.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("Selecciona un proveedor activo"));

        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < productIds.size(); index++) {
            dev.nexo.erp.catalog.Quantities.validate(quantities.get(index), catalog.product(productIds.get(index)).unitCode());
            total = total.add(quantities.get(index).multiply(unitCosts.get(index)));
        }
        total = money(total);
        BigDecimal subtotal = money(total.divide(IGV_FACTOR, 8, RoundingMode.HALF_UP));
        BigDecimal tax = total.subtract(subtotal);

        jdbc.sql("""
                insert into purchase (
                    branch_id, supplier_id, supplier_document_type, supplier_series, supplier_number,
                    issue_date, currency_code, status, inventory_status, subtotal, tax_amount, total,
                    created_by, notes
                ) values (
                    :branchId, :supplierId, :documentType, :series, :number,
                    :issueDate, 'PEN', 'RECEIVED', 'PENDING', :subtotal, :tax, :total, :createdBy, :notes
                )
                """)
            .param("branchId", catalog.mainBranchId()).param("supplierId", supplierId)
            .param("documentType", documentType).param("series", upperNullable(series))
            .param("number", number.strip()).param("issueDate", issueDate == null ? LocalDate.now() : issueDate)
            .param("subtotal", subtotal).param("tax", tax).param("total", total)
            .param("createdBy", catalog.userId(username)).param("notes", nullable(notes)).update();

        long purchaseId = jdbc.sql("""
                select id from purchase
                where supplier_id = :supplierId and supplier_document_type = :documentType
                  and ((supplier_series = :series) or (supplier_series is null and :series is null))
                  and supplier_number = :number
                """)
            .param("supplierId", supplierId).param("documentType", documentType)
            .param("series", upperNullable(series)).param("number", number.strip())
            .query(Long.class).single();

        for (int index = 0; index < productIds.size(); index++) {
            ProductInfo product = product(productIds.get(index));
            BigDecimal lineTotal = money(quantities.get(index).multiply(unitCosts.get(index)));
            BigDecimal lineSubtotal = money(lineTotal.divide(IGV_FACTOR, 8, RoundingMode.HALF_UP));
            String lot = valueAt(lots, index);
            LocalDate expiration = valueAt(expirations, index);
            jdbc.sql("""
                    insert into purchase_line (
                        purchase_id, line_number, product_id, description, quantity, unit_cost,
                        tax_amount, line_total, lot_number, expiration_date
                    ) values (
                        :purchaseId, :line, :productId, :description, :quantity, :unitCost,
                        :tax, :total, :lot, :expiration
                    )
                    """)
                .param("purchaseId", purchaseId).param("line", index + 1)
                .param("productId", product.id()).param("description", product.name())
                .param("quantity", quantities.get(index)).param("unitCost", unitCosts.get(index))
                .param("tax", lineTotal.subtract(lineSubtotal)).param("total", lineTotal)
                .param("lot", nullable(lot)).param("expiration", expiration).update();

        }
        audit(username, "PURCHASE_CREATE", purchaseId,
            "Documento registrado por " + total.toPlainString() + "; recepción de almacén pendiente");
        return purchaseId;
    }

    @Transactional
    public void receive(long purchaseId, String username) {
        PurchaseState state = jdbc.sql("""
                select status, inventory_status from purchase where id = :id for update
                """)
            .param("id", purchaseId)
            .query((rs, row) -> new PurchaseState(rs.getString("status"), rs.getString("inventory_status")))
            .optional().orElseThrow(() -> new IllegalArgumentException("La compra no existe"));
        if ("CANCELLED".equals(state.status())) {
            throw new IllegalArgumentException("No se puede recibir mercadería de una compra anulada");
        }
        if ("RECEIVED".equals(state.inventoryStatus())) {
            throw new IllegalArgumentException("La mercadería ya fue ingresada al almacén");
        }

        List<ReceiptLine> lines = jdbc.sql("""
                select pl.product_id, pl.quantity, pl.lot_number, pl.expiration_date,
                       p.stock_controlled
                from purchase_line pl
                join product p on p.id = pl.product_id
                where pl.purchase_id = :id
                order by pl.line_number
                """)
            .param("id", purchaseId)
            .query((rs, row) -> new ReceiptLine(rs.getLong("product_id"), rs.getBigDecimal("quantity"),
                rs.getString("lot_number"), rs.getObject("expiration_date", LocalDate.class),
                rs.getBoolean("stock_controlled")))
            .list();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("La compra no tiene productos para recibir");
        }
        lines.stream().map(ReceiptLine::productId).distinct().sorted().forEach(id ->
            jdbc.sql("select id from product where id = :id for update").param("id", id)
                .query(Long.class).single());
        for (ReceiptLine line : lines) {
            if (line.stockControlled()) {
                catalog.insertMovement(line.productId(), line.quantity(), "PURCHASE_IN", "PURCHASE",
                    String.valueOf(purchaseId), line.lotNumber(), line.expirationDate(),
                    "Recepción física confirmada", username);
            }
        }
        jdbc.sql("""
                update purchase set inventory_status = 'RECEIVED', inventory_received_at = current_timestamp,
                    inventory_received_by = :userId, updated_at = current_timestamp where id = :id
                """)
            .param("userId", catalog.userId(username)).param("id", purchaseId).update();
        audit(username, "PURCHASE_RECEIVE", purchaseId, "Ingreso físico confirmado en almacén");
    }

    @Transactional
    public void cancel(long purchaseId, String reason, String username) {
        if (reason == null || reason.isBlank() || reason.strip().length() < 5) {
            throw new IllegalArgumentException("Explica el motivo de anulación con al menos 5 caracteres");
        }
        String status = jdbc.sql("select status from purchase where id = :id for update")
            .param("id", purchaseId).query(String.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("La compra no existe"));
        if ("CANCELLED".equals(status)) {
            throw new IllegalArgumentException("La compra ya está anulada");
        }
        if (!"RECEIVED".equals(status)) {
            throw new IllegalArgumentException("La compra no se encuentra en un estado anulable");
        }

        List<StockEntry> entries = jdbc.sql("""
                select product_id, quantity, lot_number, expiration_date
                from inventory_movement
                where reference_type = 'PURCHASE' and reference_id = :reference
                  and movement_type = 'PURCHASE_IN'
                order by id
                """)
            .param("reference", String.valueOf(purchaseId))
            .query((rs, row) -> new StockEntry(rs.getLong("product_id"), rs.getBigDecimal("quantity"),
                rs.getString("lot_number"), rs.getObject("expiration_date", LocalDate.class)))
            .list();

        var requiredByProduct = new java.util.HashMap<Long, BigDecimal>();
        entries.forEach(entry -> requiredByProduct.merge(entry.productId(), entry.quantity(), BigDecimal::add));
        requiredByProduct.keySet().stream().sorted().forEach(id -> jdbc.sql(
                "select id from product where id = :id for update")
            .param("id", id).query(Long.class).single());
        for (var required : requiredByProduct.entrySet()) {
            if (catalog.stock(required.getKey()).compareTo(required.getValue()) < 0) {
                audit(username,"NEGATIVE_STOCK_NOTICE",purchaseId,"Reversion con saldo negativo: producto "+required.getKey());
            }
        }
        for (StockEntry entry : entries) {
            catalog.insertMovement(entry.productId(), entry.quantity().negate(), "PURCHASE_REVERSAL",
                "PURCHASE_CANCEL", String.valueOf(purchaseId), entry.lotNumber(), entry.expirationDate(),
                "Reversión por anulación de compra: " + reason.strip(), username);
        }
        jdbc.sql("""
                update purchase set status = 'CANCELLED',
                    inventory_status = case when inventory_status = 'RECEIVED' then 'REVERSED' else 'CANCELLED' end,
                    cancellation_reason = :reason,
                    cancelled_by = :userId, cancelled_at = current_timestamp,
                    updated_at = current_timestamp where id = :id
                """)
            .param("reason", reason.strip()).param("userId", catalog.userId(username))
            .param("id", purchaseId).update();
        audit(username, "PURCHASE_CANCEL", purchaseId, "Compra anulada: " + reason.strip());
    }

    private void validateLines(List<Long> productIds, List<BigDecimal> quantities, List<BigDecimal> unitCosts) {
        if (productIds == null || productIds.isEmpty()
            || quantities == null || unitCosts == null
            || productIds.size() != quantities.size() || productIds.size() != unitCosts.size()) {
            throw new IllegalArgumentException("Agrega al menos una línea válida a la compra");
        }
        for (int index = 0; index < productIds.size(); index++) {
            if (quantities.get(index) == null || quantities.get(index).signum() <= 0
                || unitCosts.get(index) == null || unitCosts.get(index).signum() < 0) {
                throw new IllegalArgumentException("Las cantidades deben ser positivas y los costos no negativos");
            }
        }
    }

    private ProductInfo product(long id) {
        return jdbc.sql("select id, name, stock_controlled from product where id = :id and active = true")
            .param("id", id)
            .query((rs, row) -> new ProductInfo(rs.getLong("id"), rs.getString("name"), rs.getBoolean("stock_controlled")))
            .single();
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String upperNullable(String value) {
        String clean = nullable(value);
        return clean == null ? "" : clean.toUpperCase(java.util.Locale.ROOT);
    }

    private void audit(String username, String action, long purchaseId, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:actor, :action, 'PURCHASE', :resourceId,
                    'SUCCESS', :correlationId, :details)
                """)
            .param("actor", catalog.userId(username)).param("action", action)
            .param("resourceId", String.valueOf(purchaseId))
            .param("correlationId", java.util.UUID.randomUUID().toString())
            .param("details", details).update();
    }

    private <T> T valueAt(List<T> values, int index) {
        return values == null || values.size() <= index ? null : values.get(index);
    }

    private record ProductInfo(long id, String name, boolean stockControlled) { }
    private record PurchaseState(String status, String inventoryStatus) { }
    private record ReceiptLine(long productId, BigDecimal quantity, String lotNumber,
                               LocalDate expirationDate, boolean stockControlled) { }
    private record StockEntry(long productId, BigDecimal quantity, String lotNumber,
                              LocalDate expirationDate) { }

    public record PurchaseRow(long id, LocalDate issueDate, String documentType, String series,
                              String number, String currency, BigDecimal total, String status,
                              String inventoryStatus,
                              String supplierDocument, String supplierName, String createdBy,
                              OffsetDateTime createdAt) { }

    public record PurchaseHeader(long id, LocalDate issueDate, String documentType, String series,
                                 String number, String currency, BigDecimal subtotal, BigDecimal tax,
                                 BigDecimal total, String status, String inventoryStatus,
                                 OffsetDateTime inventoryReceivedAt, String notes, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt, OffsetDateTime cancelledAt,
                                 String cancellationReason, String supplierDocumentType,
                                 String supplierDocument, String supplierName, String supplierEmail,
                                 String supplierPhone, String supplierAddress, String createdBy,
                                 String cancelledBy, String inventoryReceivedBy) {
        public String label() {
            return (series == null || series.isBlank() ? "" : series + "-") + number;
        }
    }

    public record PurchaseLine(int lineNumber, String sku, String description, BigDecimal quantity,
                               BigDecimal unitCost, BigDecimal tax, BigDecimal total,
                               String lotNumber, LocalDate expirationDate) { }

    public record PurchaseDetail(PurchaseHeader header, List<PurchaseLine> lines) { }
}
