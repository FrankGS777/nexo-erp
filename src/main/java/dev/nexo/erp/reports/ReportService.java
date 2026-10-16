package dev.nexo.erp.reports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary() {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.plusMonths(1);
        return jdbc.sql("""
                select count(*) as sale_count, coalesce(sum(s.total), 0) as sales_total,
                       coalesce(sum(s.tax_amount), 0) as tax_total,
                       coalesce(sum(s.total - coalesce(p.paid, 0)), 0) as pending_total
                from sale s
                left join (
                    select sale_id, sum(amount) as paid from payment_record group by sale_id
                ) p on p.sale_id = s.id
                where s.issue_date >= :start and s.issue_date < :end and s.status <> 'CANCELLED'
                """)
            .param("start", start).param("end", end)
            .query((rs, row) -> new Summary(rs.getLong("sale_count"), rs.getBigDecimal("sales_total"),
                rs.getBigDecimal("tax_total"), rs.getBigDecimal("pending_total"), inventoryValue()))
            .single();
    }

    public List<MonthlySales> monthlySales() {
        return jdbc.sql("""
                select extract(year from issue_date) as sale_year,
                       extract(month from issue_date) as sale_month,
                       count(*) as sale_count, sum(total) as total
                from sale
                where issue_date >= :start and status <> 'CANCELLED'
                group by extract(year from issue_date), extract(month from issue_date)
                order by sale_year, sale_month
                """)
            .param("start", LocalDate.now().withDayOfMonth(1).minusMonths(11))
            .query((rs, row) -> new MonthlySales(rs.getInt("sale_year"), rs.getInt("sale_month"),
                rs.getLong("sale_count"), rs.getBigDecimal("total")))
            .list();
    }

    public List<TopCustomer> topCustomers() {
        return jdbc.sql("""
                select c.document_number, c.legal_name, count(s.id) as sale_count, sum(s.total) as total
                from sale s join customer c on c.id = s.customer_id
                where s.status <> 'CANCELLED'
                group by c.id, c.document_number, c.legal_name
                order by total desc limit 10
                """)
            .query((rs, row) -> new TopCustomer(rs.getString("document_number"), rs.getString("legal_name"),
                rs.getLong("sale_count"), rs.getBigDecimal("total")))
            .list();
    }

    public List<StockAlert> stockAlerts() {
        return jdbc.sql("""
                select p.sku, p.name, coalesce(sum(sb.quantity), 0) as stock
                from product p left join stock_balance sb on sb.product_id = p.id
                where p.active = true and p.stock_controlled = true
                group by p.id, p.sku, p.name
                having coalesce(sum(sb.quantity), 0) <= 5
                order by stock, p.name limit 30
                """)
            .query((rs, row) -> new StockAlert(rs.getString("sku"), rs.getString("name"),
                rs.getBigDecimal("stock")))
            .list();
    }

    public List<AuditRow> recentAudit() {
        return jdbc.sql("""
                select ae.occurred_at, coalesce(u.display_name, 'Sistema') as actor,
                       ae.action, ae.resource_type, ae.resource_id, ae.outcome, ae.source_ip
                from audit_event ae left join app_user u on u.id = ae.actor_user_id
                order by ae.occurred_at desc limit 50
                """)
            .query((rs, row) -> new AuditRow(rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("actor"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("outcome"), rs.getString("source_ip")))
            .list();
    }

    public byte[] salesCsv(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start) || start.plusYears(2).isBefore(end)) {
            throw new IllegalArgumentException("El rango del reporte no es válido");
        }
        var rows = jdbc.sql("""
                select s.issue_date, s.document_type, s.series, s.number, c.document_number,
                       c.legal_name, s.subtotal, s.tax_amount, s.total, s.payment_status,
                       coalesce(ed.status, 'SIN_DOCUMENTO') as fiscal_status
                from sale s join customer c on c.id = s.customer_id
                left join electronic_document ed on ed.sale_id = s.id
                where s.issue_date between :start and :end and s.status <> 'CANCELLED'
                order by s.issue_date, s.id
                """)
            .param("start", start).param("end", end)
            .query((rs, row) -> List.of(rs.getString("issue_date"), rs.getString("document_type"),
                rs.getString("series") + "-" + rs.getLong("number"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getBigDecimal("subtotal").toPlainString(),
                rs.getBigDecimal("tax_amount").toPlainString(), rs.getBigDecimal("total").toPlainString(),
                rs.getString("payment_status"), rs.getString("fiscal_status")))
            .list();
        StringBuilder csv = new StringBuilder("\uFEFFfecha,tipo,comprobante,documento_cliente,cliente,subtotal,igv,total,pago,estado_fiscal\r\n");
        rows.forEach(row -> csv.append(row.stream().map(this::csv).collect(java.util.stream.Collectors.joining(",")))
            .append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private BigDecimal inventoryValue() {
        return jdbc.sql("""
                select coalesce(sum(coalesce(sb.quantity, 0) * p.sale_price), 0)
                from product p left join stock_balance sb on sb.product_id = p.id
                where p.active = true
                """)
            .query(BigDecimal.class).single();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        String leading=safe.stripLeading();
        if((!leading.isEmpty() && "=+@-".indexOf(leading.charAt(0))>=0) || safe.startsWith("\t") || safe.startsWith("\r") || safe.startsWith("\n"))safe="'"+safe;
        safe=safe.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    public record Summary(long saleCount, BigDecimal salesTotal, BigDecimal taxTotal,
                          BigDecimal pendingTotal, BigDecimal inventoryValue) { }
    public record MonthlySales(int year, int month, long count, BigDecimal total) {
        public String label() { return String.format("%02d/%d", month, year); }
    }
    public record TopCustomer(String document, String name, long count, BigDecimal total) { }
    public record StockAlert(String sku, String name, BigDecimal stock) { }
    public record AuditRow(OffsetDateTime occurredAt, String actor, String action, String resourceType,
                           String resourceId, String outcome, String sourceIp) { }
}
