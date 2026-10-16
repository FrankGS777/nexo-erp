package dev.nexo.erp.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private static final Set<String> PRODUCT_IMAGES = Set.of("/images/catalog.svg");

    private final JdbcClient jdbc;
    private final dev.nexo.erp.commercial.CommercialAccess access;

    public CatalogService(JdbcClient jdbc, dev.nexo.erp.commercial.CommercialAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<CustomerRow> customers(String query) {
        String term = "%" + normalize(query) + "%";
        return jdbc.sql("""
                select id, document_type, document_number, legal_name, trade_name, contact_name,
                       email, phone, address, ubigeo, country_code, active
                from customer
                where (lower(legal_name) like :term or lower(document_number) like :term
                   or lower(coalesce(trade_name, '')) like :term
                   or lower(coalesce(email, '')) like :term or lower(coalesce(phone, '')) like :term
                   or lower(coalesce(contact_name, '')) like :term)
                   and (:unrestricted=true or assigned_seller_id=:seller)
                order by active desc, legal_name
                """)
            .param("term", term)
            .param("unrestricted", access.currentUsername()==null || access.allCustomers(access.currentUsername()))
            .param("seller", access.currentUsername()==null ? -1L : access.userId(access.currentUsername()))
            .query((rs, row) -> new CustomerRow(
                rs.getLong("id"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("contact_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("ubigeo"), rs.getString("country_code"), rs.getBoolean("active")))
            .list();
    }

    public CustomerRow customer(long id) {
        if (access.currentUsername()!=null) access.customer(id,access.currentUsername());
        return jdbc.sql("""
                select id, document_type, document_number, legal_name, trade_name, contact_name,
                       email, phone, address, ubigeo, country_code, active
                from customer where id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new CustomerRow(
                rs.getLong("id"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("contact_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("ubigeo"), rs.getString("country_code"), rs.getBoolean("active")))
            .optional().orElseThrow(() -> new IllegalArgumentException("El cliente no existe"));
    }

    public List<ProductRow> products(String query, boolean onlyActive) {
        return productRows(query,onlyActive,false);
    }

    private List<ProductRow> productRows(String query,boolean onlyActive,boolean includeRetired) {
        String term = "%" + normalize(query) + "%";
        String activeFilter = onlyActive ? " and p.active = true\n" : "\n";
        return jdbc.sql("""
                select p.id, p.sku, p.name, p.commercial_description, p.category, p.brand,
                       p.presentation, p.image_path, p.sunat_product_code, p.barcode,
                       p.unit_code, p.igv_affectation_code, p.tax_rate, p.sale_price,
                       p.purchase_price, p.minimum_stock, p.stock_controlled, p.active,p.net_reference_price,
                       coalesce(sum(sb.quantity), 0) as stock
                from product p
                left join stock_balance sb on sb.product_id = p.id
                where (lower(p.name) like :term or lower(p.sku) like :term
                   or lower(coalesce(p.category, '')) like :term
                   or lower(coalesce(p.barcode, '')) like :term
                   or lower(coalesce(p.presentation, '')) like :term)
                """ + activeFilter + (includeRetired?"":" and (p.volume_litres is null or p.volume_litres<>1) and p.sku not like 'NC-%-1L'\n") + """
                group by p.id, p.sku, p.name, p.commercial_description, p.category, p.brand,
                         p.presentation, p.image_path, p.sunat_product_code, p.barcode,
                         p.unit_code, p.igv_affectation_code, p.tax_rate, p.sale_price,
                         p.purchase_price, p.minimum_stock, p.stock_controlled, p.active,p.net_reference_price
                order by p.active desc, p.name
                """)
            .param("term", term)
            .query((rs, row) -> new ProductRow(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("commercial_description"), rs.getString("category"), rs.getString("brand"),
                rs.getString("presentation"), rs.getString("image_path"), rs.getString("sunat_product_code"),
                rs.getString("barcode"), rs.getString("unit_code"), rs.getString("igv_affectation_code"),
                rs.getBigDecimal("tax_rate"), rs.getBigDecimal("sale_price"),
                rs.getBigDecimal("purchase_price"), rs.getBigDecimal("minimum_stock"),
                rs.getBoolean("stock_controlled"), rs.getBoolean("active"), rs.getBigDecimal("stock"),rs.getBigDecimal("net_reference_price")))
            .list();
    }

    public ProductRow product(long id) {
        return productRows("", false,true).stream().filter(item -> item.id() == id).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("El producto no existe"));
    }

    public List<String> productImages() {
        return PRODUCT_IMAGES.stream().sorted().toList();
    }

    public List<SupplierRow> suppliers(String query, boolean onlyActive) {
        String activeFilter = onlyActive ? " and active = true" : "";
        return jdbc.sql("""
                select id, document_type, document_number, legal_name, trade_name, contact_name,
                       email, phone, address, ubigeo, country_code, active
                from supplier
                where (lower(legal_name) like :term or lower(document_number) like :term
                   or lower(coalesce(trade_name, '')) like :term)
                """ + activeFilter + " order by active desc, legal_name")
            .param("term", "%" + normalize(query) + "%")
            .query((rs, row) -> new SupplierRow(
                rs.getLong("id"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("contact_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("ubigeo"), rs.getString("country_code"), rs.getBoolean("active")))
            .list();
    }

    public SupplierRow supplier(long id) {
        return jdbc.sql("""
                select id, document_type, document_number, legal_name, trade_name, contact_name,
                       email, phone, address, ubigeo, country_code, active
                from supplier where id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new SupplierRow(
                rs.getLong("id"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("contact_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("ubigeo"), rs.getString("country_code"), rs.getBoolean("active")))
            .optional().orElseThrow(() -> new IllegalArgumentException("El proveedor no existe"));
    }

    public List<MovementRow> movements() {
        return jdbc.sql("""
                select im.id, im.occurred_at, im.movement_type, im.quantity, im.reference_type,
                       im.reference_id, im.lot_number, im.expiration_date, im.notes,
                       p.sku, p.name as product_name, b.name as branch_name, u.display_name as actor_name
                from inventory_movement im
                join product p on p.id = im.product_id
                join branch b on b.id = im.branch_id
                join app_user u on u.id = im.actor_user_id
                order by im.occurred_at desc
                limit 100
                """)
            .query((rs, row) -> new MovementRow(
                rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("movement_type"), rs.getBigDecimal("quantity"),
                rs.getString("reference_type"), rs.getString("reference_id"),
                rs.getString("lot_number"), rs.getObject("expiration_date", LocalDate.class),
                rs.getString("notes"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("branch_name"), rs.getString("actor_name")))
            .list();
    }

    @Transactional
    public void createCustomer(String type, String number, String name, String email, String address,
                               String username) {
        createCustomer(type, number, name, null, null, email, null, address, null, "PE", username);
    }

    @Transactional
    public void createCustomer(String type, String number, String name, String tradeName, String contactName,
                               String email, String phone, String address, String ubigeo, String countryCode,
                               String username) {
        require(number, "El documento es obligatorio");
        require(name, "El nombre o razón social es obligatorio");
        validateIdentityDocument(type, number);
        validateContact(email, phone, ubigeo, countryCode);
        jdbc.sql("""
                insert into customer (document_type, document_number, legal_name, trade_name, contact_name,
                                      email, phone, address, ubigeo, country_code)
                values (:type, :number, :name, :tradeName, :contactName, :email, :phone, :address, :ubigeo, :country)
                """)
            .param("type", clean(type, "6"))
            .param("number", number.strip())
            .param("name", name.strip())
            .param("tradeName", nullable(tradeName)).param("contactName", nullable(contactName))
            .param("email", nullable(email))
            .param("phone", nullable(phone))
            .param("address", nullable(address))
            .param("ubigeo", nullable(ubigeo)).param("country", clean(countryCode, "PE").toUpperCase(Locale.ROOT))
            .update();
        long id = jdbc.sql("select id from customer where document_type = :type and document_number = :number")
            .param("type", clean(type, "6")).param("number", number.strip()).query(Long.class).single();
        if (!access.allCustomers(username)) jdbc.sql("update customer set assigned_seller_id=:seller where id=:id")
            .param("seller",access.userId(username)).param("id",id).update();
        audit(username, "CUSTOMER_CREATE", "CUSTOMER", id, name.strip());
    }

    @Transactional
    public void updateCustomer(long id, String name, String email, String address, String username) {
        CustomerRow current = customer(id);
        updateCustomer(id, name, current.tradeName(), current.contactName(), email, current.phone(), address,
            current.ubigeo(), current.countryCode(), username);
    }

    @Transactional
    public void updateCustomer(long id, String name, String tradeName, String contactName, String email,
                               String phone, String address, String ubigeo, String countryCode, String username) {
        access.customer(id,username);
        require(name, "El nombre o razón social es obligatorio");
        validateContact(email, phone, ubigeo, countryCode);
        jdbc.sql("""
                update customer set legal_name = :name, trade_name = :tradeName, contact_name = :contactName,
                    email = :email, phone = :phone, address = :address, ubigeo = :ubigeo, country_code = :country,
                    updated_at = current_timestamp where id = :id
                """)
            .param("name", name.strip()).param("tradeName", nullable(tradeName))
            .param("contactName", nullable(contactName)).param("email", nullable(email))
            .param("phone", nullable(phone)).param("address", nullable(address)).param("ubigeo", nullable(ubigeo))
            .param("country", clean(countryCode, "PE").toUpperCase(Locale.ROOT)).param("id", id).update();
        audit(username, "CUSTOMER_UPDATE", "CUSTOMER", id, null);
    }

    @Transactional
    public void createProduct(
        String sku,
        String name,
        String unitCode,
        BigDecimal salePrice,
        boolean stockControlled,
        BigDecimal initialStock,
        String username
    ) {
        createProduct(sku, name, null, null, "NEXO DEMO", null, null, null, null, unitCode,
            "10", new BigDecimal("18"), salePrice, BigDecimal.ZERO, BigDecimal.ZERO, stockControlled,
            initialStock, username);
    }

    @Transactional
    public void createProduct(String sku, String name, String commercialDescription, String category, String brand,
                              String presentation, String imagePath, String sunatProductCode, String barcode,
                              String unitCode, String igvAffectationCode, BigDecimal taxRate, BigDecimal salePrice,
                              BigDecimal purchasePrice, BigDecimal minimumStock, boolean stockControlled,
                              BigDecimal initialStock, String username) {
        require(sku, "El código es obligatorio");
        require(name, "El nombre es obligatorio");
        validatePresentation(presentation);
        positiveOrZero(defaultZero(initialStock),"El stock inicial no puede ser negativo");
        Quantities.validate(defaultZero(initialStock),clean(unitCode,"NIU"));
        validateProduct(unitCode, igvAffectationCode, taxRate, salePrice, purchasePrice, minimumStock,
            imagePath, sunatProductCode, barcode);
        if (!sku.strip().matches("[A-Za-z0-9._-]{2,60}")) {
            throw new IllegalArgumentException("El código solo puede usar letras, números, punto, guion y guion bajo");
        }
        jdbc.sql("""
                insert into product (sku, name, commercial_description, category, brand, presentation, image_path,
                    sunat_product_code, barcode, unit_code, igv_affectation_code, tax_rate, sale_price,
                    purchase_price, minimum_stock, stock_controlled)
                values (:sku, :name, :description, :category, :brand, :presentation, :imagePath,
                    :sunatCode, :barcode, :unitCode, :igvCode, :taxRate, :salePrice,
                    :purchasePrice, :minimumStock, :stockControlled)
                """)
            .param("sku", sku.strip().toUpperCase(Locale.ROOT))
            .param("name", name.strip())
            .param("description", nullable(commercialDescription)).param("category", nullable(category))
            .param("brand", clean(brand, "NEXO DEMO")).param("presentation", nullable(presentation))
            .param("imagePath", nullable(imagePath)).param("sunatCode", nullable(sunatProductCode))
            .param("barcode", nullable(barcode))
            .param("unitCode", clean(unitCode, "NIU"))
            .param("igvCode", clean(igvAffectationCode, "10")).param("taxRate", taxRate)
            .param("salePrice", salePrice)
            .param("purchasePrice", defaultZero(purchasePrice)).param("minimumStock", defaultZero(minimumStock))
            .param("stockControlled", stockControlled)
            .update();

        long productId = jdbc.sql("select id from product where sku = :sku")
            .param("sku", sku.strip().toUpperCase(Locale.ROOT)).query(Long.class).single();
        if (stockControlled && initialStock != null && initialStock.signum() > 0) {
            insertMovement(productId, initialStock, "INITIAL", "INITIAL", String.valueOf(productId), null, null,
                "Stock inicial", username);
        }
        audit(username, "PRODUCT_CREATE", "PRODUCT", productId, sku.strip());
    }

    @Transactional
    public void updateProduct(long id, String name, String unitCode, BigDecimal salePrice,
                              boolean stockControlled, String username) {
        ProductRow current = product(id);
        updateProduct(id, name, current.commercialDescription(), current.category(), current.brand(),
            current.presentation(), current.imagePath(), current.sunatProductCode(), current.barcode(), unitCode,
            current.igvAffectationCode(), current.taxRate(), salePrice, current.purchasePrice(),
            current.minimumStock(), stockControlled, username);
    }

    @Transactional
    public void updateProduct(long id, String name, String commercialDescription, String category, String brand,
                              String presentation, String imagePath, String sunatProductCode, String barcode,
                              String unitCode, String igvAffectationCode, BigDecimal taxRate, BigDecimal salePrice,
                              BigDecimal purchasePrice, BigDecimal minimumStock, boolean stockControlled,
                              String username) {
        require(name, "El nombre es obligatorio");
        validatePresentation(presentation);
        validateProduct(unitCode, igvAffectationCode, taxRate, salePrice, purchasePrice, minimumStock,
            imagePath, sunatProductCode, barcode);
        jdbc.sql("""
                update product set name = :name, commercial_description = :description, category = :category,
                    brand = :brand, presentation = :presentation, image_path = :imagePath,
                    sunat_product_code = :sunatCode, barcode = :barcode, unit_code = :unitCode,
                    igv_affectation_code = :igvCode, tax_rate = :taxRate, sale_price = :salePrice,
                    purchase_price = :purchasePrice, minimum_stock = :minimumStock,
                    stock_controlled = :stockControlled, updated_at = current_timestamp where id = :id
                """)
            .param("name", name.strip()).param("description", nullable(commercialDescription))
            .param("category", nullable(category)).param("brand", clean(brand, "NEXO DEMO"))
            .param("presentation", nullable(presentation)).param("imagePath", nullable(imagePath))
            .param("sunatCode", nullable(sunatProductCode)).param("barcode", nullable(barcode))
            .param("unitCode", clean(unitCode, "NIU")).param("igvCode", clean(igvAffectationCode, "10"))
            .param("taxRate", taxRate).param("salePrice", salePrice)
            .param("purchasePrice", defaultZero(purchasePrice)).param("minimumStock", defaultZero(minimumStock))
            .param("stockControlled", stockControlled)
            .param("id", id).update();
        audit(username, "PRODUCT_UPDATE", "PRODUCT", id, null);
    }

    @Transactional
    public void createSupplier(
        String type, String number, String name, String email, String phone, String address,
        String username
    ) {
        createSupplier(type, number, name, null, null, email, phone, address, null, "PE", username);
    }

    @Transactional
    public void createSupplier(String type, String number, String name, String tradeName, String contactName,
                               String email, String phone, String address, String ubigeo, String countryCode,
                               String username) {
        require(number, "El documento es obligatorio");
        require(name, "La razón social es obligatoria");
        validateIdentityDocument(type, number);
        validateContact(email, phone, ubigeo, countryCode);
        jdbc.sql("""
                insert into supplier (document_type, document_number, legal_name, trade_name, contact_name,
                    email, phone, address, ubigeo, country_code)
                values (:type, :number, :name, :tradeName, :contactName, :email, :phone, :address, :ubigeo, :country)
                """)
            .param("type", clean(type, "6")).param("number", number.strip())
            .param("name", name.strip()).param("email", nullable(email))
            .param("tradeName", nullable(tradeName)).param("contactName", nullable(contactName))
            .param("phone", nullable(phone)).param("address", nullable(address))
            .param("ubigeo", nullable(ubigeo)).param("country", clean(countryCode, "PE").toUpperCase(Locale.ROOT))
            .update();
        long id = jdbc.sql("select id from supplier where document_type = :type and document_number = :number")
            .param("type", clean(type, "6")).param("number", number.strip()).query(Long.class).single();
        audit(username, "SUPPLIER_CREATE", "SUPPLIER", id, name.strip());
    }

    @Transactional
    public void updateSupplier(long id, String name, String email, String phone, String address,
                               String username) {
        SupplierRow current = supplier(id);
        updateSupplier(id, name, current.tradeName(), current.contactName(), email, phone, address,
            current.ubigeo(), current.countryCode(), username);
    }

    @Transactional
    public void updateSupplier(long id, String name, String tradeName, String contactName, String email, String phone,
                               String address, String ubigeo, String countryCode, String username) {
        require(name, "La razón social es obligatoria");
        validateContact(email, phone, ubigeo, countryCode);
        int changed = jdbc.sql("""
                update supplier set legal_name = :name, trade_name = :tradeName, contact_name = :contactName,
                    email = :email, phone = :phone, address = :address, ubigeo = :ubigeo,
                    country_code = :country, updated_at = current_timestamp where id = :id
                """)
            .param("name", name.strip()).param("tradeName", nullable(tradeName))
            .param("contactName", nullable(contactName)).param("email", nullable(email))
            .param("phone", nullable(phone)).param("address", nullable(address))
            .param("ubigeo", nullable(ubigeo)).param("country", clean(countryCode, "PE").toUpperCase(Locale.ROOT))
            .param("id", id).update();
        if (changed != 1) {
            throw new IllegalArgumentException("El proveedor no existe");
        }
        audit(username, "SUPPLIER_UPDATE", "SUPPLIER", id, null);
    }

    @Transactional
    public void adjustStock(
        long productId,
        BigDecimal quantity,
        String direction,
        String lotNumber,
        LocalDate expirationDate,
        String notes,
        String username
    ) {
        positive(quantity, "La cantidad debe ser mayor a cero");
        jdbc.sql("select id from product where id = :id and active = true for update")
            .param("id", productId).query(Long.class).single();
        BigDecimal signed = "OUT".equalsIgnoreCase(direction) ? quantity.negate() : quantity;
        insertMovement(productId, signed, "OUT".equalsIgnoreCase(direction) ? "ADJUSTMENT_OUT" : "ADJUSTMENT_IN",
            "MANUAL", null, lotNumber, expirationDate, notes, username);
        audit(username, "INVENTORY_ADJUST", "PRODUCT", productId, signed.toPlainString());
    }

    @Transactional
    public void toggle(String table, long id, String username) {
        if ("customer".equals(table)) access.customer(id,username);
        if (!List.of("customer", "product", "supplier").contains(table)) {
            throw new IllegalArgumentException("Catálogo no permitido");
        }
        if ("product".equals(table) && jdbc.sql("select count(*) from product where id=:id and (volume_litres=1 or sku like 'NC-%-1L')")
                .param("id",id).query(Long.class).single()>0)
            throw new IllegalArgumentException("La presentacion de 1 litro esta retirada del catalogo");
        jdbc.sql("update " + table + " set active = not active, updated_at = current_timestamp where id = :id")
            .param("id", id).update();
        audit(username, table.toUpperCase(Locale.ROOT) + "_TOGGLE", table.toUpperCase(Locale.ROOT), id, null);
    }

    public BigDecimal stock(long productId) {
        return jdbc.sql("select coalesce(sum(quantity), 0) from inventory_movement where product_id = :id")
            .param("id", productId).query(BigDecimal.class).single();
    }

    public long userId(String username) {
        return jdbc.sql("select id from app_user where lower(username) = :username")
            .param("username", normalize(username)).query(Long.class).single();
    }

    public long mainBranchId() {
        return jdbc.sql("select id from branch where active = true order by id limit 1")
            .query(Long.class).single();
    }

    public void insertMovement(
        long productId,
        BigDecimal quantity,
        String movementType,
        String referenceType,
        String referenceId,
        String lotNumber,
        LocalDate expirationDate,
        String notes,
        String username
    ) {
        Quantities.validate(quantity, jdbc.sql("select unit_code from product where id=:id")
            .param("id",productId).query(String.class).single());
        jdbc.sql("""
                insert into inventory_movement (
                    branch_id, product_id, movement_type, quantity, reference_type, reference_id,
                    actor_user_id, lot_number, expiration_date, notes
                ) values (
                    :branchId, :productId, :movementType, :quantity, :referenceType, :referenceId,
                    :actorId, :lotNumber, :expirationDate, :notes
                )
                """)
            .param("branchId", mainBranchId()).param("productId", productId)
            .param("movementType", movementType).param("quantity", quantity)
            .param("referenceType", referenceType).param("referenceId", referenceId)
            .param("actorId", userId(username)).param("lotNumber", nullable(lotNumber))
            .param("expirationDate", expirationDate).param("notes", nullable(notes)).update();
    }

    private void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void positive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void positiveOrZero(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private void validateIdentityDocument(String type, String number) {
        String cleanType = clean(type, "6");
        String cleanNumber = number.strip();
        if ("6".equals(cleanType) && !cleanNumber.matches("\\d{11}")) {
            throw new IllegalArgumentException("El RUC debe contener 11 dígitos");
        }
        if ("1".equals(cleanType) && !cleanNumber.matches("\\d{8}")) {
            throw new IllegalArgumentException("El DNI debe contener 8 dígitos");
        }
    }

    private void validateContact(String email, String phone, String ubigeo, String countryCode) {
        if (email != null && !email.isBlank() && !email.strip().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("El correo no tiene un formato válido");
        }
        if (phone != null && !phone.isBlank() && !phone.strip().matches("[+0-9 ()-]{6,25}")) {
            throw new IllegalArgumentException("El teléfono no tiene un formato válido");
        }
        if (ubigeo != null && !ubigeo.isBlank() && !ubigeo.strip().matches("\\d{6}")) {
            throw new IllegalArgumentException("El ubigeo debe contener 6 dígitos");
        }
        if (!clean(countryCode, "PE").matches("[A-Za-z]{2}")) {
            throw new IllegalArgumentException("El país debe indicarse con dos letras");
        }
    }

    private void validateProduct(String unitCode, String igvCode, BigDecimal taxRate, BigDecimal salePrice,
                                 BigDecimal purchasePrice, BigDecimal minimumStock, String imagePath,
                                 String sunatProductCode, String barcode) {
        if (!List.of("NIU", "KGM", "LTR", "ZZ").contains(clean(unitCode, "NIU"))) {
            throw new IllegalArgumentException("Unidad de medida no permitida");
        }
        if (!List.of("10", "20", "30", "40").contains(clean(igvCode, "10"))) {
            throw new IllegalArgumentException("Afectación de IGV no permitida");
        }
        positiveOrZero(defaultZero(taxRate), "La tasa de impuesto no puede ser negativa");
        if (defaultZero(taxRate).compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("La tasa de impuesto no puede superar 100%");
        }
        positiveOrZero(salePrice, "El precio no puede ser negativo");
        positiveOrZero(defaultZero(purchasePrice), "El costo no puede ser negativo");
        positiveOrZero(defaultZero(minimumStock), "El stock mínimo no puede ser negativo");
        Quantities.validate(defaultZero(minimumStock), clean(unitCode,"NIU"));
        if (imagePath != null && !imagePath.isBlank() && !PRODUCT_IMAGES.contains(imagePath.strip())) {
            throw new IllegalArgumentException("La imagen seleccionada no está permitida");
        }
        if (sunatProductCode != null && !sunatProductCode.isBlank() && !sunatProductCode.strip().matches("\\d{8}")) {
            throw new IllegalArgumentException("El código SUNAT debe contener 8 dígitos");
        }
        if (barcode != null && !barcode.isBlank() && !barcode.strip().matches("[A-Za-z0-9._-]{4,40}")) {
            throw new IllegalArgumentException("El código de barras no tiene un formato válido");
        }
    }

    private void validatePresentation(String presentation) {
        if(presentation!=null && java.util.regex.Pattern.compile("(?i)(?<![0-9])1\\s*(?:l|litros?)\\b").matcher(presentation).find())
            throw new IllegalArgumentException("La presentacion de 1 litro esta retirada del catalogo");
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void audit(String username, String action, String resourceType, long resourceId, String details) {
        jdbc.sql("""
                insert into audit_event (actor_user_id, action, resource_type, resource_id,
                    outcome, correlation_id, details)
                values (:actor, :action, :type, :resourceId, 'SUCCESS', :correlationId, :details)
                """)
            .param("actor", userId(username)).param("action", action).param("type", resourceType)
            .param("resourceId", String.valueOf(resourceId))
            .param("correlationId", java.util.UUID.randomUUID().toString()).param("details", details).update();
    }

    public record CustomerRow(long id, String documentType, String documentNumber, String legalName,
                              String tradeName, String contactName, String email, String phone, String address,
                              String ubigeo, String countryCode, boolean active) { }

    public record ProductRow(long id, String sku, String name, String commercialDescription, String category,
                             String brand, String presentation, String imagePath, String sunatProductCode,
                             String barcode, String unitCode, String igvAffectationCode, BigDecimal taxRate,
                             BigDecimal salePrice, BigDecimal purchasePrice, BigDecimal minimumStock,
                             boolean stockControlled, boolean active, BigDecimal stock, BigDecimal netReferencePrice) { }

    public record SupplierRow(long id, String documentType, String documentNumber, String legalName,
                              String tradeName, String contactName, String email, String phone, String address,
                              String ubigeo, String countryCode, boolean active) { }

    public record MovementRow(long id, OffsetDateTime occurredAt, String movementType, BigDecimal quantity,
                              String referenceType, String referenceId, String lotNumber, LocalDate expirationDate,
                              String notes, String sku, String productName, String branchName, String actorName) { }
}
