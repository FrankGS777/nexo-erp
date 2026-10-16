package dev.nexo.erp.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.catalog.CatalogService;

@Service
public class SaleService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal IGV_FACTOR = new BigDecimal("1.18");
    private final JdbcClient jdbc;
    private final CatalogService catalog;

    public SaleService(JdbcClient jdbc, CatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public List<SaleRow> sales() {
        return jdbc.sql("""
                select s.id, s.issue_date, s.document_type, s.series, s.number, s.currency_code,
                       s.total, s.status, s.payment_status, c.document_number as customer_document,
                       c.legal_name as customer_name, ed.id as electronic_document_id,
                       ed.status as electronic_status, s.created_at
                from sale s
                join customer c on c.id = s.customer_id
                left join electronic_document ed on ed.sale_id = s.id
                order by s.issue_date desc, s.id desc
                limit 250
                """)
            .query((rs, row) -> new SaleRow(
                rs.getLong("id"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("document_type"), rs.getString("series"), rs.getLong("number"),
                rs.getString("currency_code"), rs.getBigDecimal("total"), rs.getString("status"),
                rs.getString("payment_status"), rs.getString("customer_document"),
                rs.getString("customer_name"), nullableLong(rs.getObject("electronic_document_id")),
                rs.getString("electronic_status"), rs.getObject("created_at", OffsetDateTime.class)))
            .list();
    }

    public SaleDetail detail(long id) {
        var header = jdbc.sql("""
                select s.id, s.issue_date, s.document_type, s.series, s.number, s.currency_code,
                       s.subtotal, s.tax_amount, s.total, s.status, s.payment_status,
                       s.payment_method, s.notes, c.document_type as customer_document_type,
                       c.document_number as customer_document, c.legal_name as customer_name,
                       c.email as customer_email, c.address as customer_address,
                       ed.id as electronic_document_id, ed.status as electronic_status,
                       ed.xml_sha256, ed.xml_object_key, ed.error_code, ed.error_message
                from sale s
                join customer c on c.id = s.customer_id
                left join electronic_document ed on ed.sale_id = s.id
                where s.id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new SaleHeader(
                rs.getLong("id"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("document_type"), rs.getString("series"), rs.getLong("number"),
                rs.getString("currency_code"), rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total"), rs.getString("status"),
                rs.getString("payment_status"), rs.getString("payment_method"), rs.getString("notes"),
                rs.getString("customer_document_type"), rs.getString("customer_document"),
                rs.getString("customer_name"), rs.getString("customer_email"),
                rs.getString("customer_address"), nullableLong(rs.getObject("electronic_document_id")),
                rs.getString("electronic_status"), rs.getString("xml_sha256"),
                rs.getString("xml_object_key"), rs.getString("error_code"), rs.getString("error_message")))
            .single();

        var lines = jdbc.sql("""
                select sl.line_number, sl.product_id, p.sku, sl.description, sl.unit_code,
                       sl.quantity, sl.unit_value, sl.tax_amount, sl.line_total
                from sale_line sl
                left join product p on p.id = sl.product_id
                where sl.sale_id = :id order by sl.line_number
                """)
            .param("id", id)
            .query((rs, row) -> new SaleLine(
                rs.getInt("line_number"), nullableLong(rs.getObject("product_id")), rs.getString("sku"),
                rs.getString("description"), rs.getString("unit_code"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_value"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("line_total")))
            .list();

        var payments = jdbc.sql("""
                select paid_at, amount, method, reference from payment_record
                where sale_id = :id order by paid_at desc
                """)
            .param("id", id)
            .query((rs, row) -> new PaymentRow(rs.getObject("paid_at", OffsetDateTime.class),
                rs.getBigDecimal("amount"), rs.getString("method"), rs.getString("reference")))
            .list();

        var adjustments = jdbc.sql("""
                select id, document_type, series, number, issue_date, reason_code, reason, total, status
                from adjustment_document where original_sale_id = :id order by id desc
                """)
            .param("id", id)
            .query((rs, row) -> new AdjustmentRow(rs.getLong("id"), rs.getString("document_type"),
                rs.getString("series"), rs.getLong("number"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("reason_code"), rs.getString("reason"), rs.getBigDecimal("total"),
                rs.getString("status")))
            .list();

        return new SaleDetail(header, lines, payments, adjustments);
    }

    @Transactional
    public long create(long customerId, String documentType, LocalDate issueDate,
                       List<Long> productIds, List<BigDecimal> quantities,
                       String paymentMethod, String notes, String username) {
        return create(customerId, documentType, issueDate, productIds, quantities, paymentMethod, notes,
            new SaleOptions("PEN", BigDecimal.ONE, "0101", "CONTADO", null, null, null, null, null,
                null, null), username);
    }

    @Transactional
    public long create(long customerId, String documentType, LocalDate issueDate,
                       List<Long> productIds, List<BigDecimal> quantities,
                       String paymentMethod, String notes, SaleOptions options, String username) {
        return createInternal(customerId, documentType, issueDate, productIds, quantities, paymentMethod,
            notes, options, username, null, true);
    }

    @Transactional
    public long createFromOrder(long customerId, List<Long> productIds, List<BigDecimal> quantities,
                                List<BigDecimal> netPrices, SaleOptions options, String username) {
        if (netPrices.size()!=productIds.size() || netPrices.stream().anyMatch(p -> p==null || p.signum()<=0))
            throw new IllegalArgumentException("Precios de pedido invalidos");
        return createInternal(customerId,"01",LocalDate.now(),productIds,quantities,null,
            "Preparado desde pedido; salida fisica pendiente",options,username,netPrices,false);
    }

    private long createInternal(long customerId, String documentType, LocalDate issueDate,
                       List<Long> productIds, List<BigDecimal> quantities,
                       String paymentMethod, String notes, SaleOptions options, String username,
                       List<BigDecimal> netPrices, boolean moveStock) {
        validateDocumentType(documentType);
        validateLines(productIds, quantities);
        ensureCustomerAllowed(customerId, documentType);
        SaleOptions fiscal = validateOptions(options, issueDate);
        long branchId = catalog.mainBranchId();
        SeriesNumber sequence = allocateSeries(branchId, documentType);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        var products = new java.util.ArrayList<ProductInfo>();
        var requestedByProduct = new java.util.HashMap<Long, BigDecimal>();
        for (int index = 0; index < productIds.size(); index++) {
            ProductInfo product = product(productIds.get(index));
            if (netPrices == null && (product.salePrice() == null || product.salePrice().signum() <= 0)) {
                throw new IllegalArgumentException("El producto " + product.name()
                    + " no tiene precio de venta vigente. Define y aprueba su precio en un pedido antes de facturar.");
            }
            if ("0200".equals(fiscal.operationType()) && !"40".equals(product.igvAffectationCode())) {
                throw new IllegalArgumentException("Una exportación solo admite productos configurados como exportación");
            }
            if ("0101".equals(fiscal.operationType()) && "40".equals(product.igvAffectationCode())) {
                throw new IllegalArgumentException("Un producto de exportación no puede incluirse en una venta interna");
            }
            BigDecimal quantity = quantities.get(index);
            dev.nexo.erp.catalog.Quantities.validate(quantity, product.unitCode());
            products.add(product);
            requestedByProduct.merge(product.id(), quantity, BigDecimal::add);
            BigDecimal lineValue = netPrices==null ? taxableValue(money(product.salePrice().multiply(quantity)), product)
                : money(netPrices.get(index).multiply(quantity));
            BigDecimal lineTotal = netPrices==null ? money(product.salePrice().multiply(quantity))
                : lineValue.add(money(lineValue.multiply(taxDivisor(product).subtract(BigDecimal.ONE))));
            total = total.add(lineTotal);
            subtotal = subtotal.add(lineValue);
            tax = tax.add(lineTotal.subtract(lineValue));
        }
        requestedByProduct.keySet().stream().sorted().forEach(this::lockProduct);
        total = money(total);
        subtotal = money(subtotal);
        tax = money(tax);
        BigDecimal detractionAmount = fiscal.detractionPercent() == null ? null
            : money(total.multiply(fiscal.detractionPercent()).divide(HUNDRED, 8, RoundingMode.HALF_UP));

        jdbc.sql("""
                insert into sale (branch_id, customer_id, document_type, series, number, issue_date,
                    currency_code, status, subtotal, tax_amount, total, payment_status,
                    payment_method, notes, created_by, operation_type, payment_condition, due_date,
                    exchange_rate, purchase_order_reference, related_guide_reference,
                    delivery_address, delivery_ubigeo, detraction_code, detraction_percent, detraction_amount)
                values (:branchId, :customerId, :documentType, :series, :number, :issueDate,
                    :currency, 'REGISTERED', :subtotal, :tax, :total, 'PENDING',
                    :paymentMethod, :notes, :createdBy, :operationType, :paymentCondition, :dueDate,
                    :exchangeRate, :purchaseOrder, :guide, :deliveryAddress, :deliveryUbigeo,
                    :detractionCode, :detractionPercent, :detractionAmount)
                """)
            .param("branchId", branchId).param("customerId", customerId)
            .param("documentType", documentType).param("series", sequence.series())
            .param("number", sequence.number()).param("issueDate", issueDate == null ? LocalDate.now() : issueDate)
            .param("currency", fiscal.currency())
            .param("subtotal", subtotal).param("tax", tax).param("total", total)
            .param("paymentMethod", nullable(paymentMethod)).param("notes", nullable(notes))
            .param("operationType", fiscal.operationType()).param("paymentCondition", fiscal.paymentCondition())
            .param("dueDate", fiscal.dueDate()).param("exchangeRate", fiscal.exchangeRate())
            .param("purchaseOrder", nullable(fiscal.purchaseOrderReference()))
            .param("guide", nullable(fiscal.relatedGuideReference()))
            .param("deliveryAddress", nullable(fiscal.deliveryAddress()))
            .param("deliveryUbigeo", nullable(fiscal.deliveryUbigeo()))
            .param("detractionCode", nullable(fiscal.detractionCode()))
            .param("detractionPercent", fiscal.detractionPercent()).param("detractionAmount", detractionAmount)
            .param("createdBy", catalog.userId(username)).update();

        long saleId = jdbc.sql("""
                select id from sale where document_type = :type and series = :series and number = :number
                """)
            .param("type", documentType).param("series", sequence.series()).param("number", sequence.number())
            .query(Long.class).single();

        for (int index = 0; index < products.size(); index++) {
            ProductInfo product = products.get(index);
            BigDecimal quantity = quantities.get(index);
            BigDecimal lineValue = netPrices==null ? taxableValue(money(product.salePrice().multiply(quantity)), product)
                : money(netPrices.get(index).multiply(quantity));
            BigDecimal lineTotal = netPrices==null ? money(product.salePrice().multiply(quantity))
                : lineValue.add(money(lineValue.multiply(taxDivisor(product).subtract(BigDecimal.ONE))));
            BigDecimal divisor = taxDivisor(product);
            BigDecimal unitValue = netPrices==null ? product.salePrice().divide(divisor, 6, RoundingMode.HALF_UP) : netPrices.get(index);
            jdbc.sql("""
                    insert into sale_line (sale_id, line_number, product_id, description, unit_code,
                        quantity, unit_value, tax_amount, line_total, igv_affectation_code, tax_rate,
                        sunat_product_code)
                    values (:saleId, :line, :productId, :description, :unitCode,
                        :quantity, :unitValue, :tax, :lineTotal, :igvCode, :taxRate, :sunatCode)
                    """)
                .param("saleId", saleId).param("line", index + 1).param("productId", product.id())
                .param("description", product.name()).param("unitCode", product.unitCode())
                .param("quantity", quantity).param("unitValue", unitValue)
                .param("tax", lineTotal.subtract(lineValue)).param("lineTotal", lineTotal)
                .param("igvCode", product.igvAffectationCode()).param("taxRate", product.taxRate())
                .param("sunatCode", nullable(product.sunatProductCode())).update();
            if (product.stockControlled() && moveStock) {
                catalog.insertMovement(product.id(), quantity.negate(), "SALE_OUT", "SALE",
                    String.valueOf(saleId), null, null, "Salida por venta " + sequence.label(), username);
            }
        }

        if ("CREDITO".equals(fiscal.paymentCondition())) {
            jdbc.sql("""
                    insert into sale_installment (sale_id, installment_number, due_date, amount)
                    values (:saleId, 1, :dueDate, :amount)
                    """).param("saleId", saleId).param("dueDate", fiscal.dueDate()).param("amount", total).update();
        }

        jdbc.sql("""
                insert into electronic_document (sale_id, document_type, series, number, status)
                values (:saleId, :type, :series, :number, 'PENDING_GENERATION')
                """)
            .param("saleId", saleId).param("type", documentType)
            .param("series", sequence.series()).param("number", sequence.number()).update();
        long documentId = electronicDocumentId(documentType, sequence.series(), sequence.number());
        recordDocumentEvent(documentId, null, "PENDING_GENERATION", "Venta registrada", username);
        audit(username, "SALE_CREATE", "SALE", saleId, "SUCCESS", sequence.label());
        return saleId;
    }

    @Transactional
    public void registerPayment(long saleId, BigDecimal amount, String method, String reference, String username) {
        registerPayment(saleId,amount,method,reference,UUID.randomUUID().toString(),username);
    }

    @Transactional
    public void registerPayment(long saleId, BigDecimal amount, String method, String reference, String requestKey, String username) {
        UUID.fromString(requestKey);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(money(amount))!=0 || amount.compareTo(new BigDecimal("99999999"))>0) {
            throw new IllegalArgumentException("El importe del pago debe ser mayor a cero");
        }
        if (method == null || !List.of("CASH","TRANSFER","CARD","YAPE_PLIN").contains(method)) {
            throw new IllegalArgumentException("Selecciona el medio de pago");
        }
        if(reference!=null&&reference.length()>120)throw new IllegalArgumentException("Referencia demasiado larga");
        BigDecimal total = jdbc.sql("select total from sale where id = :id for update")
            .param("id", saleId).query(BigDecimal.class).single();
        if("CANCELLED".equals(jdbc.sql("select status from sale where id=:id").param("id",saleId).query(String.class).single()))
            throw new IllegalArgumentException("No se pueden aplicar pagos a una venta anulada");
        var previous=jdbc.sql("select sale_id from payment_record where request_key=:key").param("key",requestKey).query(Long.class).optional();
        if(previous.isPresent()) {
            if(previous.get()!=saleId)throw new IllegalArgumentException("Solicitud de pago duplicada");
            return;
        }
        if(reference!=null&&!reference.isBlank() && jdbc.sql("select count(*) from payment_record where sale_id=:id and method=:method and reference=:ref")
            .param("id",saleId).param("method",method).param("ref",reference.strip()).query(Long.class).single()>0)
            throw new IllegalArgumentException("Esta referencia ya esta aplicada a la factura");
        BigDecimal paid = jdbc.sql("select coalesce(sum(amount), 0) from payment_record where sale_id = :id")
            .param("id", saleId).query(BigDecimal.class).single();
        if (paid.add(amount).compareTo(total) > 0) {
            throw new IllegalArgumentException("El pago excede el saldo pendiente");
        }
        jdbc.sql("""
                insert into payment_record (sale_id, amount, method, reference, registered_by,request_key)
                values (:saleId, :amount, :method, :reference, :userId,:key)
                """)
            .param("saleId", saleId).param("amount", money(amount)).param("method", method.strip())
            .param("reference", nullable(reference)).param("userId", catalog.userId(username)).param("key",requestKey).update();
        BigDecimal newPaid = paid.add(amount);
        String status = newPaid.compareTo(total) >= 0 ? "PAID" : "PARTIAL";
        jdbc.sql("update sale set payment_status = :status, payment_method = :method, updated_at = current_timestamp where id = :id")
            .param("status", status).param("method", method.strip()).param("id", saleId).update();
        audit(username, "PAYMENT_CREATE", "SALE", saleId, "SUCCESS", money(amount).toPlainString());
    }

    @Transactional
    public long createAdjustment(long saleId, String documentType, String reasonCode, String reason,
                                 BigDecimal amount, String username) {
        if (!List.of("07", "08").contains(documentType)) {
            throw new IllegalArgumentException("Tipo de nota inválido");
        }
        if (reason == null || reason.isBlank() || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("El motivo y su código son obligatorios");
        }
        String cleanReasonCode = reasonCode.strip();
        if ("07".equals(documentType)
            && !List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10")
                .contains(cleanReasonCode)) {
            throw new IllegalArgumentException("Código de motivo inválido para una nota de crédito");
        }
        if ("08".equals(documentType) && !List.of("01", "02", "03").contains(cleanReasonCode)) {
            throw new IllegalArgumentException("Código de motivo inválido para una nota de débito");
        }
        OriginalSale original = jdbc.sql("select total, document_type, currency_code from sale where id = :id")
            .param("id", saleId)
            .query((rs, row) -> new OriginalSale(rs.getBigDecimal("total"), rs.getString("document_type"),
                rs.getString("currency_code")))
            .single();
        if (!"PEN".equals(original.currencyCode())) {
            throw new IllegalArgumentException("Las notas en otra moneda aún no están habilitadas para emisión electrónica");
        }
        Long unsupportedLines = jdbc.sql("""
                select count(*) from sale_line
                where sale_id = :saleId and (igv_affectation_code <> '10' or tax_rate <> 18)
                """)
            .param("saleId", saleId).query(Long.class).single();
        if (unsupportedLines > 0) {
            throw new IllegalArgumentException("Esta venta requiere una nota con tratamiento tributario por ítem, aún no habilitada");
        }
        BigDecimal originalTotal = original.total();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El importe de la nota debe ser positivo");
        }
        if ("07".equals(documentType)) {
            if (amount.compareTo(originalTotal) > 0) {
                throw new IllegalArgumentException("Una nota de crédito no puede superar la venta");
            }
            if (List.of("01", "02", "06").contains(cleanReasonCode)
                && amount.compareTo(originalTotal) != 0) {
                throw new IllegalArgumentException("Ese motivo exige una nota de crédito por el total de la venta");
            }
            BigDecimal priorCredits = jdbc.sql("""
                    select coalesce(sum(total), 0) from adjustment_document
                    where original_sale_id = :saleId and document_type = '07' and status <> 'CANCELLED'
                    """)
                .param("saleId", saleId).query(BigDecimal.class).single();
            if (priorCredits.add(amount).compareTo(originalTotal) > 0) {
                throw new IllegalArgumentException("Las notas de crédito acumuladas no pueden superar la venta");
            }
        }
        long branchId = catalog.mainBranchId();
        SeriesNumber sequence = allocateSeries(branchId, documentType,
            "03".equals(original.documentType()) ? "B" : "F");
        BigDecimal total = money(amount);
        BigDecimal subtotal = money(total.divide(IGV_FACTOR, 8, RoundingMode.HALF_UP));
        BigDecimal tax = total.subtract(subtotal);
        jdbc.sql("""
                insert into adjustment_document (original_sale_id, document_type, series, number,
                    reason_code, reason, issue_date, currency_code, subtotal, tax_amount, total,
                    status, created_by)
                values (:saleId, :type, :series, :number, :reasonCode, :reason, current_date,
                    'PEN', :subtotal, :tax, :total, 'REGISTERED', :createdBy)
                """)
            .param("saleId", saleId).param("type", documentType).param("series", sequence.series())
            .param("number", sequence.number()).param("reasonCode", cleanReasonCode)
            .param("reason", reason.strip()).param("subtotal", subtotal).param("tax", tax)
            .param("total", total).param("createdBy", catalog.userId(username)).update();
        long adjustmentId = jdbc.sql("""
                select id from adjustment_document where document_type = :type and series = :series and number = :number
                """)
            .param("type", documentType).param("series", sequence.series()).param("number", sequence.number())
            .query(Long.class).single();
        jdbc.sql("""
                insert into electronic_document (adjustment_id, document_type, series, number, status)
                values (:adjustmentId, :type, :series, :number, 'PENDING_GENERATION')
                """)
            .param("adjustmentId", adjustmentId).param("type", documentType)
            .param("series", sequence.series()).param("number", sequence.number()).update();
        long documentId = electronicDocumentId(documentType, sequence.series(), sequence.number());
        recordDocumentEvent(documentId, null, "PENDING_GENERATION", "Nota registrada", username);
        audit(username, "ADJUSTMENT_CREATE", "ADJUSTMENT_DOCUMENT", adjustmentId, "SUCCESS", sequence.label());
        return adjustmentId;
    }

    public long electronicDocumentId(String type, String series, long number) {
        return jdbc.sql("select id from electronic_document where document_type = :type and series = :series and number = :number")
            .param("type", type).param("series", series).param("number", number).query(Long.class).single();
    }

    public void recordDocumentEvent(long documentId, String previousStatus, String newStatus,
                                    String message, String username) {
        jdbc.sql("""
                insert into electronic_document_event (electronic_document_id, previous_status,
                    new_status, message, actor_user_id)
                values (:documentId, :previous, :next, :message, :actor)
                """)
            .param("documentId", documentId).param("previous", previousStatus)
            .param("next", newStatus).param("message", nullable(message))
            .param("actor", username == null ? null : catalog.userId(username)).update();
    }

    private SeriesNumber allocateSeries(long branchId, String type) {
        return allocateSeries(branchId, type, null);
    }

    private SeriesNumber allocateSeries(long branchId, String type, String prefix) {
        SeriesNumber value = jdbc.sql("""
                select series, next_number from document_series
                where branch_id = :branchId and document_type = :type and active = true
                  and (coalesce(:prefix, '') = '' or series like :seriesPrefix)
                order by id limit 1 for update
                """)
            .param("branchId", branchId).param("type", type)
            .param("prefix", prefix).param("seriesPrefix", prefix == null ? null : prefix + "%")
            .query((rs, row) -> new SeriesNumber(rs.getString("series"), rs.getLong("next_number")))
            .optional().orElseThrow(() -> new IllegalArgumentException("No existe una serie activa para este tipo de documento"));
        jdbc.sql("""
                update document_series set next_number = next_number + 1, version = version + 1
                where branch_id = :branchId and document_type = :type and series = :series
                """)
            .param("branchId", branchId).param("type", type).param("series", value.series()).update();
        return value;
    }

    private ProductInfo product(long id) {
        return jdbc.sql("""
                select id, name, unit_code, sale_price, stock_controlled, igv_affectation_code,
                       tax_rate, sunat_product_code
                from product where id = :id and active = true
                """)
            .param("id", id)
            .query((rs, row) -> new ProductInfo(rs.getLong("id"), rs.getString("name"),
                rs.getString("unit_code"), rs.getBigDecimal("sale_price"), rs.getBoolean("stock_controlled"),
                rs.getString("igv_affectation_code"), rs.getBigDecimal("tax_rate"),
                rs.getString("sunat_product_code")))
            .single();
    }

    private SaleOptions validateOptions(SaleOptions value, LocalDate issueDate) {
        SaleOptions options = value == null ? new SaleOptions("PEN", BigDecimal.ONE, "0101", "CONTADO", null,
            null, null, null, null, null, null) : value;
        String currency = options.currency() == null ? "PEN" : options.currency().strip().toUpperCase();
        if (!List.of("PEN", "USD").contains(currency)) {
            throw new IllegalArgumentException("Moneda no permitida");
        }
        BigDecimal exchangeRate = options.exchangeRate() == null ? BigDecimal.ONE : options.exchangeRate();
        if (exchangeRate.signum() <= 0 || exchangeRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("El tipo de cambio no es válido");
        }
        String operationType = options.operationType() == null ? "0101" : options.operationType().strip();
        if (!List.of("0101", "0200").contains(operationType)) {
            throw new IllegalArgumentException("Tipo de operación SUNAT no permitido");
        }
        String condition = options.paymentCondition() == null ? "CONTADO"
            : options.paymentCondition().strip().toUpperCase();
        if (!List.of("CONTADO", "CREDITO").contains(condition)) {
            throw new IllegalArgumentException("Condición de pago no permitida");
        }
        LocalDate effectiveIssueDate = issueDate == null ? LocalDate.now() : issueDate;
        if ("CREDITO".equals(condition) &&
            (options.dueDate() == null || options.dueDate().isBefore(effectiveIssueDate))) {
            throw new IllegalArgumentException("Una venta al crédito requiere vencimiento igual o posterior a la emisión");
        }
        if (options.deliveryUbigeo() != null && !options.deliveryUbigeo().isBlank()
            && !options.deliveryUbigeo().strip().matches("\\d{6}")) {
            throw new IllegalArgumentException("El ubigeo de entrega debe contener 6 dígitos");
        }
        return new SaleOptions(currency, exchangeRate, operationType, condition, options.dueDate(),
            options.purchaseOrderReference(), options.relatedGuideReference(), options.deliveryAddress(),
            options.deliveryUbigeo(), null, null);
    }

    private BigDecimal taxableValue(BigDecimal lineTotal, ProductInfo product) {
        return money(lineTotal.divide(taxDivisor(product), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal taxDivisor(ProductInfo product) {
        if (!"10".equals(product.igvAffectationCode()) || product.taxRate().signum() == 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(product.taxRate().divide(HUNDRED, 8, RoundingMode.HALF_UP));
    }

    private void lockProduct(long id) {
        jdbc.sql("select id from product where id = :id for update")
            .param("id", id).query(Long.class).single();
    }

    private void ensureCustomerAllowed(long customerId, String documentType) {
        var customer = jdbc.sql("select document_type, document_number from customer where id = :id and active = true")
            .param("id", customerId)
            .query((rs, row) -> new CustomerDocument(rs.getString("document_type"), rs.getString("document_number")))
            .single();
        if ("01".equals(documentType) && (!"6".equals(customer.type()) || customer.number().length() != 11)) {
            throw new IllegalArgumentException("Una factura requiere un cliente con RUC válido");
        }
    }

    private void validateDocumentType(String type) {
        if (!List.of("01", "03").contains(type)) {
            throw new IllegalArgumentException("Solo se permiten facturas o boletas");
        }
    }

    private void validateLines(List<Long> productIds, List<BigDecimal> quantities) {
        if (productIds == null || productIds.isEmpty() || quantities == null || productIds.size() != quantities.size()) {
            throw new IllegalArgumentException("Agrega al menos una línea válida a la venta");
        }
        for (BigDecimal quantity : quantities) {
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("Las cantidades deben ser mayores a cero");
            }
        }
    }

    private void audit(String username, String action, String resourceType, long resourceId,
                       String outcome, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:actor, :action, :resourceType, :resourceId, :outcome, :correlationId, :details)
                """)
            .param("actor", catalog.userId(username)).param("action", action)
            .param("resourceType", resourceType).param("resourceId", String.valueOf(resourceId))
            .param("outcome", outcome).param("correlationId", java.util.UUID.randomUUID().toString())
            .param("details", details).update();
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record SeriesNumber(String series, long number) {
        String label() { return series + "-" + String.format("%08d", number); }
    }
    private record OriginalSale(BigDecimal total, String documentType, String currencyCode) { }
    private record ProductInfo(long id, String name, String unitCode, BigDecimal salePrice,
                               boolean stockControlled, String igvAffectationCode, BigDecimal taxRate,
                               String sunatProductCode) { }
    private record CustomerDocument(String type, String number) { }

    public record SaleRow(long id, LocalDate issueDate, String documentType, String series, long number,
                          String currency, BigDecimal total, String status, String paymentStatus,
                          String customerDocument, String customerName, Long electronicDocumentId,
                          String electronicStatus, OffsetDateTime createdAt) {
        public String label() { return series + "-" + String.format("%08d", number); }
    }

    public record SaleHeader(long id, LocalDate issueDate, String documentType, String series, long number,
                             String currency, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
                             String status, String paymentStatus, String paymentMethod, String notes,
                             String customerDocumentType, String customerDocument, String customerName,
                             String customerEmail, String customerAddress, Long electronicDocumentId,
                             String electronicStatus, String xmlSha256, String xmlObjectKey,
                             String errorCode, String errorMessage) {
        public String label() { return series + "-" + String.format("%08d", number); }
    }
    public record SaleLine(int lineNumber, Long productId, String sku, String description, String unitCode,
                           BigDecimal quantity, BigDecimal unitValue, BigDecimal taxAmount,
                           BigDecimal lineTotal) { }
    public record PaymentRow(OffsetDateTime paidAt, BigDecimal amount, String method, String reference) { }
    public record AdjustmentRow(long id, String documentType, String series, long number, LocalDate issueDate,
                                String reasonCode, String reason, BigDecimal total, String status) {
        public String label() { return series + "-" + String.format("%08d", number); }
    }
    public record SaleDetail(SaleHeader header, List<SaleLine> lines, List<PaymentRow> payments,
                             List<AdjustmentRow> adjustments) { }
    public record SaleOptions(String currency, BigDecimal exchangeRate, String operationType,
                              String paymentCondition, LocalDate dueDate, String purchaseOrderReference,
                              String relatedGuideReference, String deliveryAddress, String deliveryUbigeo,
                              String detractionCode, BigDecimal detractionPercent) { }
}
