package dev.nexo.erp.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic fixtures, deliberately unrelated to any company or transaction. */
@Component
@Order(30)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.demo.seed", havingValue="true", matchIfMissing=true)
public class DemoData implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    public DemoData(JdbcClient jdbc, DataSource dataSource) { this.jdbc = jdbc; this.dataSource = dataSource; }

    @Override @Transactional
    public void run(ApplicationArguments arguments) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) {
                throw new IllegalStateException("The public edition requires a disposable in-memory database");
            }
        }
        if (jdbc.sql("select count(*) from customer").query(Long.class).single() > 0) return;
        long actor = jdbc.sql("select id from app_user order by id limit 1").query(Long.class).single();
        String[] customers = {"Estudio Horizonte", "Mercado Aurora", "Hotel Brisa", "Taller Prisma",
            "Cafe Alameda", "Oficinas Central", "Distribuidora Delta", "Libreria Norte"};
        for (int i = 0; i < customers.length; i++) {
            jdbc.sql("insert into customer(document_type,document_number,legal_name,trade_name,email,address,phone,assigned_seller_id,credit_limit,credit_days) values('6',:doc,:name,:name,:email,:address,'000000000',:actor,5000,30)")
                .param("doc", String.format("00000000%03d", i + 1)).param("name", customers[i] + " Demo")
                .param("email", "cliente" + (i+1) + "@example.invalid")
                .param("address", "Avenida Ejemplo " + (100+i) + ", Ciudad Demo").param("actor", actor).update();
        }
        String[] products = {"Cuaderno A5", "Organizador de escritorio", "Lampara de mesa", "Botella termica",
            "Mochila urbana", "Set de marcadores", "Soporte para laptop", "Agenda semanal"};
        BigDecimal[] prices = {new BigDecimal("18"),new BigDecimal("42"),new BigDecimal("89"),new BigDecimal("55"),
            new BigDecimal("129"),new BigDecimal("24"),new BigDecimal("95"),new BigDecimal("32")};
        for (int i = 0; i < products.length; i++) {
            jdbc.sql("insert into product(sku,name,unit_code,sale_price,net_reference_price,price_origin,category,brand,presentation,image_path,minimum_stock) values(:sku,:name,'NIU',:price,:price,'DEMO',:category,'Nexo Demo','Unidad','/images/catalog.svg',10)")
                .param("sku", String.format("NX-%03d", i+1)).param("name", products[i]).param("price", prices[i])
                .param("category", i%2==0 ? "Oficina" : "Accesorios").update();
        }
        var customerIds = jdbc.sql("select id from customer order by id").query(Long.class).list();
        var productIds = jdbc.sql("select id from product order by id").query(Long.class).list();
        long branch = jdbc.sql("select id from branch order by id limit 1").query(Long.class).single();
        for (int i=0;i<productIds.size();i++) {
            jdbc.sql("insert into inventory_movement(branch_id,product_id,movement_type,quantity,reference_type,reference_id,actor_user_id,notes) values(:branch,:product,'INITIAL',:stock,'DEMO','SYNTHETIC',:actor,'Inventario ficticio')")
                .param("branch",branch).param("product",productIds.get(i)).param("stock",i==3?4:80+i*13).param("actor",actor).update();
        }
        String[] statuses = {"CONFIRMED", "SENT", "DRAFT", "PREPARED", "PARTIAL", "CONFIRMED", "SENT", "DRAFT"};
        for (int i=0;i<16;i++) {
            int index=i%products.length;
            BigDecimal quantity=BigDecimal.valueOf(4+i);
            BigDecimal subtotal=prices[index].multiply(quantity);
            BigDecimal tax=subtotal.multiply(new BigDecimal("0.18")).setScale(2);
            String reference=String.format("COT-DEMO-%04d",i+1);
            jdbc.sql("insert into commercial_order(reference,customer_id,seller_id,status,valid_until,requested_date,promised_date,delivery_address,subtotal,tax_amount,total,notes) values(:reference,:customer,:actor,:status,:valid,:date,:promised,'Direccion de ejemplo',:subtotal,:tax,:total,'Registro sintetico, sin validez comercial')")
                .param("reference",reference).param("customer",customerIds.get(index)).param("actor",actor)
                .param("status",statuses[index]).param("valid",LocalDate.now().plusDays(10))
                .param("date",LocalDate.now().minusDays(i%8)).param("promised",LocalDate.now().plusDays(i%4))
                .param("subtotal",subtotal).param("tax",tax).param("total",subtotal.add(tax)).update();
            long order=jdbc.sql("select id from commercial_order where reference=:reference").param("reference",reference).query(Long.class).single();
            jdbc.sql("insert into commercial_order_line(order_id,product_id,description,quantity,net_price,tax_rate,price_origin,price_approved) values(:order,:product,:name,:quantity,:price,18,'DEMO',true)")
                .param("order",order).param("product",productIds.get(index)).param("name",products[index])
                .param("quantity",quantity).param("price",prices[index]).update();
            jdbc.sql("insert into sale(branch_id,customer_id,document_type,series,number,issue_date,status,subtotal,tax_amount,total,created_by,payment_status,notes) values(:branch,:customer,'01','F001',:number,:date,'REGISTERED',:subtotal,:tax,:total,:actor,'PENDING','DEMO - SIN VALIDEZ FISCAL')")
                .param("branch",branch).param("customer",customerIds.get(index)).param("number",i+1)
                .param("date",LocalDate.now().withDayOfMonth(1).plusDays(i%Math.min(LocalDate.now().getDayOfMonth(),15)))
                .param("subtotal",subtotal).param("tax",tax).param("total",subtotal.add(tax)).param("actor",actor).update();
        }
        jdbc.sql("update document_series set next_number=100").update();
        jdbc.sql("update fiscal_configuration set active=false,provider_code='DEMO',environment='TEST',endpoint_url=null,sol_username_reference=null,certificate_reference=null").update();
    }
}
