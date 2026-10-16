package dev.nexo.erp.commercial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.nexo.erp.catalog.CatalogService;
import dev.nexo.erp.sales.SaleService;

@Service
public class CommercialService {
    private final JdbcClient jdbc;
    private final CommercialAccess access;
    private final CatalogService catalog;
    private final SaleService sales;
    public CommercialService(JdbcClient jdbc, CommercialAccess access, CatalogService catalog, SaleService sales) {
        this.jdbc=jdbc;this.access=access;this.catalog=catalog;this.sales=sales;
    }
    public List<Map<String,Object>> customers(String username) {
        return jdbc.sql("""
            select c.*,u.display_name as seller_name from customer c
            left join app_user u on u.id=c.assigned_seller_id
            where (:all=true or c.assigned_seller_id=:user) order by c.legal_name
            """).param("all",access.allCustomers(username)).param("user",access.userId(username)).query().listOfRows();
    }
    public List<Map<String,Object>> sellers() {
        return jdbc.sql("""
            select distinct u.id,u.display_name from app_user u join user_role ur on ur.user_id=u.id
            join role r on r.id=ur.role_id where u.active=true and r.code='VENTAS' order by u.display_name
            """).query().listOfRows();
    }
    public List<CustomerOption> findCustomers(String username,String query) {
        access.require(username,"QUOTE_MANAGE");
        if(query.length()>120)throw new IllegalArgumentException("Busqueda demasiado larga");
        String[] terms=searchText(query).split("\\s+");
        return customers(username).stream().filter(c->Boolean.TRUE.equals(c.get("active")))
            .filter(c->{String text=searchText(c.get("legal_name")+" "+c.get("document_number")+" "+
                Objects.toString(c.get("phone"),"")+" "+Objects.toString(c.get("email"),""));
                return Arrays.stream(terms).allMatch(text::contains);})
            .sorted(Comparator.comparing(c->!Objects.equals(c.get("document_number"),query.trim())))
            .limit(50).map(c->new CustomerOption(number(c,"id"),String.valueOf(c.get("legal_name")),
                String.valueOf(c.get("document_number")),Objects.toString(c.get("address"),""),
                Objects.toString(c.get("phone"),""),Objects.toString(c.get("email"),""))).toList();
    }
    private static String searchText(String text) {
        return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
    }
    public record CustomerOption(long id,String name,String document,String address,String phone,String email) { }
    public List<Map<String,Object>> orders(String username,String query,String status) {
        return jdbc.sql("""
            select o.*,c.legal_name,c.document_number,u.display_name as seller_name,
              (select count(*) from commercial_order_line l where l.order_id=o.id and l.price_approved=false) as unapproved
            from commercial_order o join customer c on c.id=o.customer_id join app_user u on u.id=o.seller_id
            where (:all=true or c.assigned_seller_id=:user)
              and (lower(c.legal_name) like :q or lower(o.reference) like :q or c.document_number like :q)
              and (:status='' or o.status=:status)
            order by o.created_at desc limit 300
            """).param("all",access.allCustomers(username)).param("user",access.userId(username))
            .param("q","%"+query.toLowerCase(Locale.ROOT)+"%").param("status",status).query().listOfRows();
    }
    public Map<String,Object> order(long id,String username) {
        var o=jdbc.sql("""
            select o.*,c.legal_name,c.document_number,c.address,c.credit_limit,c.credit_days,
            u.display_name as seller_name from commercial_order o join customer c on c.id=o.customer_id
            join app_user u on u.id=o.seller_id where o.id=:id
            """).param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Pedido no encontrado"));
        access.customer(number(o,"customer_id"),username);return o;
    }
    public List<Map<String,Object>> lines(long orderId,String username) {
        order(orderId,username);
        return jdbc.sql("""
            select l.*,p.sku,p.presentation,p.image_path,p.volume_litres,
            round(l.quantity*l.net_price,2) as line_net,
            coalesce((select sum(quantity) from stock_balance where product_id=l.product_id),0) as stock
            from commercial_order_line l join product p on p.id=l.product_id where l.order_id=:id order by l.id
            """).param("id",orderId).query().listOfRows();
    }
    public List<Map<String,Object>> prices(long customerId,String username,String query) {
        access.customer(customerId,username);
        return jdbc.sql("""
            select p.id,p.sku,p.name,p.category,p.presentation,p.image_path,p.volume_litres,p.tax_rate,p.barcode,p.unit_code,
            p.net_reference_price,p.estimate_basis,coalesce(cp.net_price,p.net_reference_price) as net_price,
            coalesce(cp.origin,p.price_origin) as origin,coalesce(cp.approved,false) as approved,
            pe.invoice_number,pe.issue_date,pe.quantity as source_quantity,
            round(coalesce(cp.net_price,p.net_reference_price)*(1+p.tax_rate/100),2) as gross_price,
            round(coalesce(cp.net_price,p.net_reference_price)/nullif(p.volume_litres,0),4) as price_litre,
            case when p.price_origin='REFERENCE' then round((coalesce(cp.net_price,p.net_reference_price)/nullif(p.net_reference_price,0)-1)*100,2) end as difference_percent,
            coalesce((select sum(quantity) from stock_balance where product_id=p.id),0) as stock
            from product p left join customer_price cp on cp.product_id=p.id and cp.customer_id=:c
            left join price_evidence pe on pe.id=cp.evidence_id
            where p.active=true
             and (lower(p.name) like :q or lower(p.sku) like :q or lower(p.category) like :q or lower(p.barcode) like :q)
            order by p.category,p.name,p.volume_litres
            """).param("c",customerId).param("q","%"+query.toLowerCase(Locale.ROOT)+"%").query().listOfRows();
    }
    @Transactional
    public void assign(long customerId,Long sellerId,String username) {
        access.require(username,"PRICE_APPROVE");
        if (sellerId!=null && sellers().stream().noneMatch(s->number(s,"id")==sellerId))
            throw new IllegalArgumentException("Selecciona un vendedor activo");
        jdbc.sql("update customer set assigned_seller_id=:seller where id=:id").param("seller",sellerId).param("id",customerId).update();
        audit(username,"PORTFOLIO_ASSIGN",customerId,"Vendedor: "+sellerId);
    }
    @Transactional
    public void credit(long customerId,BigDecimal limit,int days,String username) {
        access.require(username,"CREDIT_APPROVE");
        amount(limit,true);if(days<0||days>365)throw new IllegalArgumentException("Plazo fuera de rango");
        jdbc.sql("select id from customer where id=:id for update").param("id",customerId).query(Long.class).single();
        jdbc.sql("update customer set credit_limit=:limit,credit_days=:days,credit_approved_by=:user where id=:id")
            .param("limit",money(limit)).param("days",days).param("user",access.userId(username)).param("id",customerId).update();
        audit(username,"CREDIT_APPROVE",customerId,limit+" PEN / "+days+" dias");
    }
    @Transactional
    public void approvePrice(long customerId,long productId,BigDecimal net,String reason,String username) {
        access.require(username,"PRICE_APPROVE");amount(net,false);required(reason,500);
        jdbc.sql("select id from customer where id=:id for update").param("id",customerId).query(Long.class).single();
        jdbc.sql("delete from customer_price where customer_id=:c and product_id=:p").param("c",customerId).param("p",productId).update();
        jdbc.sql("""
            insert into customer_price(customer_id,product_id,net_price,origin,approved,approved_by)
            values (:c,:p,:net,'APPROVED',true,:user)
            """).param("c",customerId).param("p",productId).param("net",net.setScale(6,RoundingMode.HALF_UP))
            .param("user",access.userId(username)).update();
        audit(username,"PRICE_APPROVE",customerId,"Producto "+productId+" / "+net+" / "+reason);
    }
    @Transactional
    public long create(long customerId,LocalDate requested,String address,String condition,String notes,String username) {
        access.require(username,"QUOTE_MANAGE");access.customer(customerId,username);
        var c=jdbc.sql("select * from customer where id=:id and active=true").param("id",customerId).query().singleRow();
        if(!List.of("CONTADO","CREDITO").contains(condition))throw new IllegalArgumentException("Condicion invalida");
        if(requested!=null&&requested.isBefore(LocalDate.now()))throw new IllegalArgumentException("La fecha solicitada ya paso");
        bounded(notes,1000);bounded(address,300);
        String ref="COT-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.sql("""
            insert into commercial_order(reference,customer_id,seller_id,valid_until,requested_date,delivery_address,payment_condition,notes)
            values (:ref,:c,:seller,:until,:requested,:address,:condition,:notes)
            """).param("ref",ref).param("c",customerId)
            .param("seller",c.get("assigned_seller_id")==null?access.userId(username):c.get("assigned_seller_id"))
            .param("until",LocalDate.now().plusDays(7)).param("requested",requested).param("address",address)
            .param("condition",condition).param("notes",notes).update();
        long id=jdbc.sql("select id from commercial_order where reference=:ref").param("ref",ref).query(Long.class).single();
        audit(username,"QUOTE_CREATE",id,ref);return id;
    }
    @Transactional
    public void line(long id,long productId,BigDecimal quantity,BigDecimal proposed,String username) {
        access.require(username,"QUOTE_MANAGE");var o=locked(id,username);state(o,"DRAFT");amount(quantity,false);
        var p=prices(number(o,"customer_id"),username,"").stream().filter(x->number(x,"id")==productId).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Producto no disponible"));
        dev.nexo.erp.catalog.Quantities.validate(quantity,String.valueOf(p.get("unit_code")));
        BigDecimal base=(BigDecimal)p.get("net_price");BigDecimal net=proposed==null?base:proposed;amount(net,false);
        boolean approved=Boolean.TRUE.equals(p.get("approved")) && base!=null && base.compareTo(net)==0;
        String origin=base!=null&&base.compareTo(net)==0?String.valueOf(p.get("origin")):"PROPOSED";
        jdbc.sql("delete from commercial_order_line where order_id=:id and product_id=:p").param("id",id).param("p",productId).update();
        jdbc.sql("""
            insert into commercial_order_line(order_id,product_id,description,quantity,net_price,tax_rate,price_origin,price_approved)
            values (:id,:p,:description,:qty,:net,:tax,:origin,:approved)
            """).param("id",id).param("p",productId).param("description",p.get("name"))
            .param("qty",quantity).param("net",net.setScale(6,RoundingMode.HALF_UP)).param("tax",p.get("tax_rate"))
            .param("origin",origin).param("approved",approved).update();recalculate(id);
        audit(username,"QUOTE_LINE",id,"Producto "+productId+" / "+quantity+" / "+net);
    }
    @Transactional
    public void removeLine(long id,long productId,String username) {
        access.require(username,"QUOTE_MANAGE");state(locked(id,username),"DRAFT");
        jdbc.sql("delete from commercial_order_line where order_id=:id and product_id=:p").param("id",id).param("p",productId).update();
        recalculate(id);audit(username,"QUOTE_LINE_REMOVE",id,String.valueOf(productId));
    }
    @Transactional
    public void transition(long id,String action,String reason,LocalDate promised,String username) {
        access.require(username,"QUOTE_MANAGE");var o=locked(id,username);String next;
        switch(action) {
            case "approve" -> {
                access.require(username,"PRICE_APPROVE");state(o,"DRAFT");required(reason,500);
                jdbc.sql("update commercial_order_line set price_approved=true where order_id=:id").param("id",id).update();
                audit(username,"QUOTE_PRICE_APPROVE",id,reason);return;
            }
            case "send" -> {state(o,"DRAFT");ready(id,username);next="SENT";}
            case "confirm" -> {
                state(o,"SENT");ready(id,username);required(reason,500);
                if(LocalDate.parse(o.get("valid_until").toString()).isBefore(LocalDate.now()))throw new IllegalArgumentException("La cotizacion vencio; crea una nueva");
                if(promised!=null&&promised.isBefore(LocalDate.now()))throw new IllegalArgumentException("Fecha prometida invalida");
                boolean reserved=reserve(id,username);
                next="CONFIRMED";
                jdbc.sql("update commercial_order set stock_reserved=:reserved where id=:id").param("reserved",reserved).param("id",id).update();
                jdbc.sql("update commercial_order set acceptance=:reason,promised_date=:date where id=:id")
                    .param("reason",reason).param("date",promised).param("id",id).update();
            }
            case "reserve" -> {
                access.require(username,"DISPATCH_MANAGE");state(o,"WAITING_STOCK","PARTIAL");
                boolean reserved=reserve(id,username);
                jdbc.sql("update commercial_order set stock_reserved=:reserved where id=:id").param("reserved",reserved).param("id",id).update();
                next="PARTIAL".equals(o.get("status"))?"PARTIAL":"CONFIRMED";
            }
            case "cancel" -> {state(o,"DRAFT","SENT","WAITING_STOCK","CONFIRMED");required(reason,500);next="CANCELLED";}
            case "close" -> {
                access.require(username,"DISPATCH_MANAGE");state(o,"PARTIAL");required(reason,500);
                if(jdbc.sql("select count(*) from commercial_dispatch where order_id=:id and status='PREPARED'").param("id",id).query(Long.class).single()>0)
                    throw new IllegalArgumentException("Hay una salida preparada pendiente");
                next="CLOSED";
            }
            default -> throw new IllegalArgumentException("Accion no permitida");
        }
        jdbc.sql("update commercial_order set status=:status,updated_at=current_timestamp,version=version+1 where id=:id")
            .param("status",next).param("id",id).update();audit(username,"ORDER_"+action.toUpperCase(Locale.ROOT),id,reason);
    }
    private boolean reserve(long id,String username) {
        var rows=lines(id,username);lockProducts(rows);
        for(var l:rows)if(available(number(l,"product_id"),id).compareTo(((BigDecimal)l.get("quantity")).subtract((BigDecimal)l.get("dispatched_quantity")))<0)return false;
        return true;
    }
    private BigDecimal available(long product,long excludingOrder) {
        BigDecimal reserved=jdbc.sql("""
            select coalesce(sum(l.quantity-l.dispatched_quantity),0) from commercial_order_line l
            join commercial_order o on o.id=l.order_id where l.product_id=:p and o.id<>:id
            and o.stock_reserved=true and o.status in ('CONFIRMED','PARTIAL','PREPARED')
            """).param("p",product).param("id",excludingOrder).query(BigDecimal.class).single();
        BigDecimal prepared=jdbc.sql("""
            select coalesce(sum(dl.quantity),0) from commercial_dispatch_line dl join commercial_dispatch d on d.id=dl.dispatch_id
            join commercial_order_line l on l.id=dl.order_line_id where l.product_id=:p and d.status='PREPARED'
            """).param("p",product).query(BigDecimal.class).single();
        return catalog.stock(product).subtract(reserved).subtract(prepared);
    }
    @Transactional
    public long prepare(long id,List<Long> lineIds,List<BigDecimal> quantities,String requestKey,String username) {
        access.require(username,"DISPATCH_MANAGE");UUID.fromString(requestKey);var o=locked(id,username);
        var existing=jdbc.sql("select id,order_id from commercial_dispatch where request_key=:key").param("key",requestKey).query().listOfRows().stream().findFirst();
        if(existing.isPresent()) {if(number(existing.get(),"order_id")!=id)throw new IllegalArgumentException("Solicitud duplicada");return number(existing.get(),"id");}
        state(o,"CONFIRMED","PARTIAL","WAITING_STOCK");
        if(lineIds.size()!=quantities.size()||lineIds.isEmpty()||lineIds.size()>100||new HashSet<>(lineIds).size()!=lineIds.size())
            throw new IllegalArgumentException("Detalle de despacho invalido");
        var rows=lines(id,username);lockProducts(rows);
        var products=new ArrayList<Long>();var qtys=new ArrayList<BigDecimal>();var prices=new ArrayList<BigDecimal>();
        var selected=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<lineIds.size();i++) {
            long lineId=lineIds.get(i);BigDecimal qty=quantities.get(i);amount(qty,true);if(qty.signum()==0)continue;
            if(qty.scale()>4)throw new IllegalArgumentException("Cantidad con demasiados decimales");
            var l=rows.stream().filter(x->number(x,"id")==lineId).findFirst().orElseThrow(()->new IllegalArgumentException("Linea ajena al pedido"));
            var currentProduct=catalog.product(number(l,"product_id"));
            dev.nexo.erp.catalog.Quantities.validate(qty,currentProduct.unitCode());
            if(!currentProduct.active())throw new IllegalArgumentException("El producto ya no esta disponible");
            if(!"10".equals(currentProduct.igvAffectationCode()) || currentProduct.taxRate().compareTo((BigDecimal)l.get("tax_rate"))!=0)
                throw new IllegalArgumentException("El tratamiento tributario cambio; revisa la cotizacion antes de facturar");
            if(qty.compareTo(((BigDecimal)l.get("quantity")).subtract((BigDecimal)l.get("dispatched_quantity")))>0)
                throw new IllegalArgumentException("La cantidad supera el saldo del pedido");
            if(available(number(l,"product_id"),id).compareTo(qty)<0)
                audit(username,"STOCK_SHORTAGE_NOTICE",id,"Producto "+l.get("product_id")+" / cantidad "+qty);
            products.add(number(l,"product_id"));qtys.add(qty);prices.add((BigDecimal)l.get("net_price"));selected.add(l);
            BigDecimal net=money(qty.multiply((BigDecimal)l.get("net_price")));
            total=total.add(net).add(money(net.multiply((BigDecimal)l.get("tax_rate")).divide(new BigDecimal("100"))));
        }
        if(products.isEmpty())throw new IllegalArgumentException("Selecciona una cantidad para facturar");
        long customerId=number(o,"customer_id");
        jdbc.sql("select id from customer where id=:id for update").param("id",customerId).query(Long.class).single();
        var c=jdbc.sql("select credit_limit,credit_days,credit_approved_by from customer where id=:id").param("id",customerId).query().singleRow();
        BigDecimal debt=jdbc.sql("""
            select coalesce(sum(s.total-coalesce((select sum(amount) from payment_record where sale_id=s.id),0)),0)
            from sale s where s.customer_id=:c and s.status<>'CANCELLED' and s.payment_status<>'PAID'
            """).param("c",customerId).query(BigDecimal.class).single();
        long overdue=jdbc.sql("select count(*) from sale where customer_id=:c and due_date<current_date and payment_status<>'PAID' and status<>'CANCELLED'")
            .param("c",customerId).query(Long.class).single();
        if(overdue>0)throw new IllegalArgumentException("Cliente con deuda vencida; administracion debe regularizarla");
        String condition=String.valueOf(o.get("payment_condition"));LocalDate due=null;
        if("CREDITO".equals(condition)) {
            if(c.get("credit_approved_by")==null||number(c,"credit_days")<=0||debt.add(total).compareTo((BigDecimal)c.get("credit_limit"))>0)
                throw new IllegalArgumentException("Credito no aprobado o limite insuficiente");
            due=LocalDate.now().plusDays(number(c,"credit_days"));
        }
        var options=new SaleService.SaleOptions("PEN",BigDecimal.ONE,"0101",condition,due,null,null,
            (String)o.get("delivery_address"),null,null,null);
        long saleId=sales.createFromOrder(customerId,products,qtys,prices,options,username);
        var profile=defaultTransportProfile(username);
        jdbc.sql("""
            insert into commercial_dispatch(order_id,sale_id,request_key,created_by,departure_address,arrival_address,
              transport_mode,carrier_ruc,carrier_name,driver_name,driver_document,driver_license,vehicle_plate,vehicle_brand)
            values (:id,:sale,:key,:user,:departure,:arrival,:mode,:carrierRuc,:carrierName,:driverName,
              :driverDocument,:driverLicense,:vehiclePlate,:vehicleBrand)
            """).param("id",id).param("sale",saleId).param("key",requestKey).param("user",access.userId(username))
            .param("departure",companyAddress(username)).param("arrival",o.get("delivery_address"))
            .param("mode",profile.get("transport_mode")).param("carrierRuc",profile.get("carrier_ruc"))
            .param("carrierName",profile.get("carrier_name")).param("driverName",profile.get("driver_name"))
            .param("driverDocument",profile.get("driver_document")).param("driverLicense",profile.get("driver_license"))
            .param("vehiclePlate",profile.get("vehicle_plate")).param("vehicleBrand",profile.get("vehicle_brand")).update();
        long dispatch=jdbc.sql("select id from commercial_dispatch where request_key=:key").param("key",requestKey).query(Long.class).single();
        for(int i=0;i<selected.size();i++) {
            long lineId=number(selected.get(i),"id");
            jdbc.sql("insert into commercial_dispatch_line(dispatch_id,order_line_id,quantity) values (:d,:l,:q)")
                .param("d",dispatch).param("l",lineId).param("q",qtys.get(i)).update();
            jdbc.sql("update commercial_order_line set dispatched_quantity=dispatched_quantity+:q where id=:l")
                .param("q",qtys.get(i)).param("l",lineId).update();
        }
        long remaining=jdbc.sql("select count(*) from commercial_order_line where order_id=:id and dispatched_quantity<quantity")
            .param("id",id).query(Long.class).single();
        jdbc.sql("update commercial_order set status=:status where id=:id").param("status",remaining>0?"PARTIAL":"PREPARED").param("id",id).update();
        audit(username,"DISPATCH_PREPARE",id,"Factura "+saleId);return dispatch;
    }
    public List<Map<String,Object>> dispatches(long id,String username) {
        order(id,username);
        return jdbc.sql("""
            select d.*,s.series,s.number,s.total,ed.status as fiscal_status from commercial_dispatch d
            join sale s on s.id=d.sale_id join electronic_document ed on ed.sale_id=s.id
            where d.order_id=:id order by d.id desc
            """).param("id",id).query().listOfRows();
    }
    public String companyAddress(String username) {
        access.require(username,"DISPATCH_MANAGE");
        return jdbc.sql("select coalesce(fiscal_address,'') from company_profile order by id limit 1").query(String.class).single();
    }
    public List<Map<String,Object>> transportProfiles(String username) {
        access.require(username,"DISPATCH_MANAGE");
        return jdbc.sql("""
            select id,profile_name,transport_mode,carrier_ruc,carrier_name,driver_name,driver_document,
              driver_license,vehicle_plate,vehicle_brand,is_default
            from company_transport_profile where active=true order by is_default desc,profile_name
            """).query().listOfRows();
    }
    private Map<String,Object> defaultTransportProfile(String username) {
        return transportProfiles(username).stream().filter(p->Boolean.TRUE.equals(p.get("is_default")))
            .findFirst().orElseGet(Collections::emptyMap);
    }
    @Transactional
    public void externalGuide(long orderId,long dispatchId,String guide,String carrier,String plate,LocalDate date,String username) {
        access.require(username,"DISPATCH_MANAGE");locked(orderId,username);
        if(guide==null||!guide.matches("(?:T[A-Z0-9]{3}|EG[0-9]{2})-[0-9]{1,8}"))throw new IllegalArgumentException("Usa una GRE remitente valida: T001-123 o EG01-123");
        if(carrier!=null&&!carrier.isBlank()&&!carrier.matches("[0-9]{11}"))throw new IllegalArgumentException("RUC del transportista invalido");
        bounded(plate,15);if(date==null||date.isBefore(LocalDate.now()))throw new IllegalArgumentException("Fecha de traslado invalida");
        int changed=jdbc.sql("""
            update commercial_dispatch set guide_number=:guide,guide_status='EXTERNAL_DECLARED',carrier_ruc=:carrier,
            vehicle_plate=:plate,transport_date=:date where id=:d and order_id=:o and status='PREPARED'
            """).param("guide",guide).param("carrier",carrier).param("plate",plate).param("date",date).param("d",dispatchId).param("o",orderId).update();
        if(changed!=1)throw new IllegalArgumentException("Despacho no disponible");audit(username,"EXTERNAL_GUIDE_DECLARED",orderId,guide);
    }
    @Transactional
    public void transportDetails(long orderId,long dispatchId,Map<String,String> fields,String username) {
        access.require(username,"DISPATCH_MANAGE");locked(orderId,username);
        Map<String,Integer> limits=Map.of("departureAddress",300,"arrivalAddress",300,"carrierRuc",11,"carrierName",220,
            "driverName",160,"driverDocument",20,"driverLicense",20,"vehiclePlate",15,"vehicleBrand",60,"profileName",80);
        for(var limit:limits.entrySet())bounded(fields.get(limit.getKey()),limit.getValue());
        String mode=fields.getOrDefault("mode","");if(!List.of("","PRIVADO","PUBLICO").contains(mode))throw new IllegalArgumentException("Modalidad invalida");
        String carrierRuc=clean(fields.get("carrierRuc"));
        String departure=clean(fields.get("departureAddress")),arrival=clean(fields.get("arrivalAddress"));
        if(departure.isBlank())throw new IllegalArgumentException("Indica la direccion de partida");
        if(arrival.isBlank())throw new IllegalArgumentException("Indica la direccion de llegada");
        if(!carrierRuc.isBlank()&&!carrierRuc.matches("[0-9]{11}"))throw new IllegalArgumentException("RUC del transportista invalido");
        BigDecimal weight=fields.getOrDefault("grossWeight","").isBlank()?null:new BigDecimal(fields.get("grossWeight"));
        Integer packages=fields.getOrDefault("packages","").isBlank()?null:Integer.valueOf(fields.get("packages"));
        if(weight!=null&&(weight.signum()<=0||weight.scale()>3||weight.compareTo(new BigDecimal("9999999"))>0))throw new IllegalArgumentException("Peso bruto invalido");
        if(packages!=null&&(packages<1||packages>9999999))throw new IllegalArgumentException("Numero de bultos invalido");
        int changed=jdbc.sql("""
            update commercial_dispatch set departure_address=:departure,arrival_address=:arrival,transport_mode=:mode,
            carrier_ruc=:carrierRuc,carrier_name=:carrier,driver_name=:driver,driver_document=:document,driver_license=:license,
            vehicle_plate=:plate,vehicle_brand=:brand,
            gross_weight=:weight,packages=:packages where id=:id and order_id=:order and status='PREPARED'
            """).param("departure",departure).param("arrival",arrival).param("mode",mode)
            .param("carrierRuc",carrierRuc).param("carrier",fields.get("carrierName")).param("driver",fields.get("driverName"))
            .param("document",fields.get("driverDocument")).param("license",fields.get("driverLicense"))
            .param("plate",fields.get("vehiclePlate")).param("brand",fields.get("vehicleBrand"))
            .param("weight",weight).param("packages",packages)
            .param("id",dispatchId).param("order",orderId).update();
        if(changed!=1)throw new IllegalArgumentException("El despacho no permite cambios");
        if("true".equals(fields.get("saveAsDefault")))saveTransportProfile(fields,carrierRuc,mode,username);
        audit(username,"TRANSPORT_DETAILS",orderId,"Despacho "+dispatchId);
    }
    private void saveTransportProfile(Map<String,String> fields,String carrierRuc,String mode,String username) {
        String name=clean(fields.get("profileName"));if(name.isBlank())name="Transporte habitual";
        long company=jdbc.sql("select id from company_profile order by id limit 1 for update").query(Long.class).single();
        jdbc.sql("update company_transport_profile set is_default=false where company_id=:company and is_default=true")
            .param("company",company).update();
        var existing=jdbc.sql("select id from company_transport_profile where company_id=:company and lower(profile_name)=lower(:name)")
            .param("company",company).param("name",name).query(Long.class).list();
        if(existing.isEmpty()) {
            jdbc.sql("""
                insert into company_transport_profile(company_id,profile_name,transport_mode,carrier_ruc,carrier_name,
                  driver_name,driver_document,driver_license,vehicle_plate,vehicle_brand,is_default,created_by)
                values (:company,:name,:mode,:ruc,:carrier,:driver,:document,:license,:plate,:brand,true,:user)
                """).param("company",company).param("name",name).param("mode",emptyToNull(mode)).param("ruc",emptyToNull(carrierRuc))
                .param("carrier",emptyToNull(fields.get("carrierName"))).param("driver",emptyToNull(fields.get("driverName")))
                .param("document",emptyToNull(fields.get("driverDocument"))).param("license",emptyToNull(fields.get("driverLicense")))
                .param("plate",emptyToNull(fields.get("vehiclePlate"))).param("brand",emptyToNull(fields.get("vehicleBrand")))
                .param("user",access.userId(username)).update();
        } else {
            jdbc.sql("""
                update company_transport_profile set profile_name=:name,transport_mode=:mode,carrier_ruc=:ruc,
                  carrier_name=:carrier,driver_name=:driver,driver_document=:document,driver_license=:license,
                  vehicle_plate=:plate,vehicle_brand=:brand,is_default=true,active=true,updated_at=current_timestamp where id=:id
                """).param("name",name).param("mode",emptyToNull(mode)).param("ruc",emptyToNull(carrierRuc))
                .param("carrier",emptyToNull(fields.get("carrierName"))).param("driver",emptyToNull(fields.get("driverName")))
                .param("document",emptyToNull(fields.get("driverDocument"))).param("license",emptyToNull(fields.get("driverLicense")))
                .param("plate",emptyToNull(fields.get("vehiclePlate"))).param("brand",emptyToNull(fields.get("vehicleBrand")))
                .param("id",existing.getFirst()).update();
        }
    }
    @Transactional
    public void depart(long orderId,long dispatchId,String username) {
        access.require(username,"DISPATCH_MANAGE");locked(orderId,username);
        var d=jdbc.sql("select * from commercial_dispatch where id=:d and order_id=:o for update")
            .param("d",dispatchId).param("o",orderId).query().singleRow();
        if("DEPARTED".equals(d.get("status")))return;
        if(!"PREPARED".equals(d.get("status")))throw new IllegalArgumentException("La salida no esta preparada");
        if(d.get("guide_number")==null)throw new IllegalArgumentException("Registra una guia emitida antes de la salida");
        String fiscal=jdbc.sql("select status from electronic_document where sale_id=:s").param("s",d.get("sale_id")).query(String.class).single();
        if(!List.of("ACCEPTED","OBSERVED").contains(fiscal))throw new IllegalArgumentException("La factura debe tener aceptacion fiscal antes de confirmar esta salida");
        var rows=jdbc.sql("select l.product_id,dl.quantity from commercial_dispatch_line dl join commercial_order_line l on l.id=dl.order_line_id where dl.dispatch_id=:d order by l.product_id")
            .param("d",dispatchId).query().listOfRows();lockProducts(rows);
        for(var l:rows) {
            long p=number(l,"product_id");BigDecimal qty=(BigDecimal)l.get("quantity");
            if(catalog.stock(p).compareTo(qty)<0)
                audit(username,"NEGATIVE_STOCK_NOTICE",orderId,"Producto "+p+" / despacho "+dispatchId);
            catalog.insertMovement(p,qty.negate(),"SALE_OUT","DISPATCH",String.valueOf(dispatchId),null,null,"Salida de pedido "+orderId,username);
        }
        jdbc.sql("update commercial_dispatch set status='DEPARTED',departed_at=current_timestamp where id=:d").param("d",dispatchId).update();
        if(jdbc.sql("select count(*) from commercial_order_line where order_id=:id and dispatched_quantity<quantity").param("id",orderId).query(Long.class).single()==0 &&
           jdbc.sql("select count(*) from commercial_dispatch where order_id=:id and status='PREPARED'").param("id",orderId).query(Long.class).single()==0)
            jdbc.sql("update commercial_order set status='COMPLETED' where id=:id").param("id",orderId).update();
        audit(username,"DISPATCH_DEPART",orderId,String.valueOf(dispatchId));
    }
    public List<Map<String,Object>> evidence(long customer,String username) {
        access.customer(customer,username);return jdbc.sql("select * from price_evidence where customer_id=:c order by issue_date desc,invoice_number desc limit 500")
            .param("c",customer).query().listOfRows();
    }
    public List<Map<String,Object>> issues(String username) {access.require(username,"PRICE_APPROVE");return jdbc.sql("select * from import_issue order by id limit 600").query().listOfRows();}
    public List<Map<String,Object>> activity(long id,String username) {
        order(id,username);return jdbc.sql("""
            select a.occurred_at,a.action,a.details,u.display_name from audit_event a left join app_user u on u.id=a.actor_user_id
            where resource_type='COMMERCIAL' and resource_id=:id order by a.id desc limit 50
            """).param("id",String.valueOf(id)).query().listOfRows();
    }
    private Map<String,Object> locked(long id,String username) {jdbc.sql("select id from commercial_order where id=:id for update").param("id",id).query(Long.class).single();return order(id,username);}
    private void ready(long id,String username) {var rows=lines(id,username);if(rows.isEmpty()||rows.stream().anyMatch(l->!Boolean.TRUE.equals(l.get("price_approved"))))throw new IllegalArgumentException("Agrega productos y solicita aprobacion de los precios pendientes");}
    private void lockProducts(List<Map<String,Object>> rows) {rows.stream().map(l->number(l,"product_id")).distinct().sorted().forEach(p->jdbc.sql("select id from product where id=:id for update").param("id",p).query(Long.class).single());}
    private void recalculate(long id) {
        BigDecimal net=BigDecimal.ZERO,tax=BigDecimal.ZERO;
        for(var l:jdbc.sql("select quantity,net_price,tax_rate from commercial_order_line where order_id=:id").param("id",id).query().listOfRows()) {
            BigDecimal line=money(((BigDecimal)l.get("quantity")).multiply((BigDecimal)l.get("net_price")));
            net=net.add(line);tax=tax.add(money(line.multiply((BigDecimal)l.get("tax_rate")).divide(new BigDecimal("100"))));
        }
        jdbc.sql("update commercial_order set subtotal=:net,tax_amount=:tax,total=:total,updated_at=current_timestamp,version=version+1 where id=:id")
            .param("net",net).param("tax",tax).param("total",net.add(tax)).param("id",id).update();
    }
    private void audit(String username,String action,long id,String detail) {
        jdbc.sql("insert into audit_event(actor_user_id,action,resource_type,resource_id,outcome,correlation_id,details) values (:u,:a,'COMMERCIAL',:id,'SUCCESS',:key,:detail)")
            .param("u",access.userId(username)).param("a",action).param("id",String.valueOf(id)).param("key",UUID.randomUUID().toString()).param("detail",detail).update();
    }
    private static void state(Map<String,Object> o,String... allowed) {if(!Arrays.asList(allowed).contains(o.get("status")))throw new IllegalArgumentException("La operacion no corresponde al estado actual");}
    private static void amount(BigDecimal n,boolean zero) {if(n==null||n.signum()<0||(!zero&&n.signum()==0)||n.compareTo(new BigDecimal("9999999"))>0||n.scale()>6)throw new IllegalArgumentException("Importe o cantidad fuera de rango");}
    private static void bounded(String s,int max) {if(s!=null&&s.length()>max)throw new IllegalArgumentException("Texto demasiado largo");}
    private static void required(String s,int max) {if(s==null||s.isBlank())throw new IllegalArgumentException("Indica el motivo o la evidencia");bounded(s,max);}
    private static String clean(String value) {return value==null?"":value.trim();}
    private static String emptyToNull(String value) {String cleaned=clean(value);return cleaned.isEmpty()?null:cleaned;}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static BigDecimal money(BigDecimal n) {return n.setScale(2,RoundingMode.HALF_UP);}
}
