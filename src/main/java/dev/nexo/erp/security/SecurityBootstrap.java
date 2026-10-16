package dev.nexo.erp.security;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.core.annotation.Order(0)
public class SecurityBootstrap implements ApplicationRunner {

    private static final List<PermissionSeed> PERMISSIONS = List.of(
        new PermissionSeed("DASHBOARD_VIEW", "Consultar el panel de inicio"),
        new PermissionSeed("DOCUMENT_VIEW", "Consultar comprobantes electrónicos"),
        new PermissionSeed("DOCUMENT_ISSUE", "Emitir comprobantes electrónicos"),
        new PermissionSeed("SALES_MANAGE", "Registrar y administrar ventas"),
        new PermissionSeed("INVENTORY_MANAGE", "Administrar productos y existencias"),
        new PermissionSeed("CUSTOMER_MANAGE", "Administrar clientes"),
        new PermissionSeed("PURCHASE_MANAGE", "Administrar compras"),
        new PermissionSeed("REPORT_VIEW", "Consultar reportes"),
        new PermissionSeed("SECURITY_ADMIN", "Administrar usuarios, roles y permisos"),
        new PermissionSeed("QUOTE_MANAGE", "Cotizaciones y pedidos propios"),
        new PermissionSeed("PORTFOLIO_ALL", "Consultar toda la cartera"),
        new PermissionSeed("PRICE_APPROVE", "Aprobar precios y asignar cartera"),
        new PermissionSeed("CREDIT_APPROVE", "Autorizar credito"),
        new PermissionSeed("DISPATCH_MANAGE", "Preparar y confirmar despachos"),
        new PermissionSeed("PAYMENT_VERIFY", "Verificar y aplicar pagos")
    );
    private static final List<RoleSeed> ROLES = List.of(
        new RoleSeed("ADMINISTRADOR", "Administrador", "Control total del sistema", PERMISSIONS.stream().map(PermissionSeed::code).toList()),
        new RoleSeed("VENTAS", "Ventas", "Cartera, cotizaciones y pedidos propios", List.of("DASHBOARD_VIEW", "QUOTE_MANAGE", "CUSTOMER_MANAGE")),
        new RoleSeed("ALMACEN", "Almacén", "Productos, compras y existencias", List.of("DASHBOARD_VIEW", "INVENTORY_MANAGE", "PURCHASE_MANAGE")),
        new RoleSeed("CONTABILIDAD", "Facturacion y administracion", "Facturacion, despacho y verificacion de pagos", List.of("DASHBOARD_VIEW", "DOCUMENT_VIEW", "DOCUMENT_ISSUE", "SALES_MANAGE", "CUSTOMER_MANAGE", "QUOTE_MANAGE", "PORTFOLIO_ALL", "DISPATCH_MANAGE", "PAYMENT_VERIFY", "PURCHASE_MANAGE", "REPORT_VIEW", "INVENTORY_MANAGE")),
        new RoleSeed("CONSULTA", "Consulta", "Acceso de solo lectura", List.of("DASHBOARD_VIEW", "DOCUMENT_VIEW", "REPORT_VIEW"))
    );

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public SecurityBootstrap(
        JdbcClient jdbc,
        PasswordEncoder passwordEncoder,
        @Value("${app.security.bootstrap-username}") String username,
        @Value("${app.security.bootstrap-password}") String password
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "Defina NEXO_ADMIN_PASSWORD para iniciar Nexo ERP de forma segura."
            );
        }

        seedPermissions();
        seedRoles();
        long roleId = roleId("ADMINISTRADOR");
        long userId = ensureBootstrapUser();

        if (!exists("select count(*) from user_role where user_id = :first and role_id = :second", userId, roleId)) {
            jdbc.sql("insert into user_role (user_id, role_id) values (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        }

    }

    private void seedPermissions() {
        for (PermissionSeed permission : PERMISSIONS) {
            if (!exists("select count(*) from permission where code = :code", permission.code())) {
                jdbc.sql("insert into permission (code, description) values (:code, :description)")
                    .param("code", permission.code())
                    .param("description", permission.description())
                    .update();
            }
        }
    }

    private void seedRoles() {
        for (RoleSeed role : ROLES) {
            if (!exists("select count(*) from role where code = :code", role.code())) {
                jdbc.sql("""
                        insert into role (code, name, description, system_role)
                        values (:code, :name, :description, true)
                        """)
                    .param("code", role.code())
                    .param("name", role.name())
                    .param("description", role.description())
                    .update();
            }
            long roleId = roleId(role.code());
            if ("VENTAS".equals(role.code())) {
                jdbc.sql("delete from role_permission where role_id=:id and permission_id in (select id from permission where code in ('DOCUMENT_VIEW','DOCUMENT_ISSUE','SALES_MANAGE','REPORT_VIEW','PORTFOLIO_ALL'))")
                    .param("id",roleId).update();
            }
            for (String permissionCode : role.permissions()) {
                long permissionId = jdbc.sql("select id from permission where code = :code")
                    .param("code", permissionCode)
                    .query(Long.class)
                    .single();
                if (!exists(
                    "select count(*) from role_permission where role_id = :first and permission_id = :second",
                    roleId,
                    permissionId
                )) {
                    jdbc.sql("insert into role_permission (role_id, permission_id) values (:roleId, :permissionId)")
                        .param("roleId", roleId)
                        .param("permissionId", permissionId)
                        .update();
                }
            }
        }
    }

    private long roleId(String code) {
        return jdbc.sql("select id from role where code = :code")
            .param("code", code)
            .query(Long.class)
            .single();
    }

    private long ensureBootstrapUser() {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        if (!exists("select count(*) from app_user where username = :code", normalized)) {
            jdbc.sql("""
                    insert into app_user (username, display_name, password_hash, active, mfa_required)
                    values (:username, :displayName, :passwordHash, true, false)
                    """)
                .param("username", normalized)
                .param("displayName", "Administrador Nexo")
                .param("passwordHash", passwordEncoder.encode(password))
                .update();
        }
        return jdbc.sql("select id from app_user where username = :username")
            .param("username", normalized)
            .query(Long.class)
            .single();
    }

    private boolean exists(String sql, Object value) {
        return jdbc.sql(sql)
            .param("code", value)
            .query(Long.class)
            .single() > 0;
    }

    private boolean exists(String sql, Object first, Object second) {
        return jdbc.sql(sql)
            .param("first", first)
            .param("second", second)
            .query(Long.class)
            .single() > 0;
    }

    private record PermissionSeed(String code, String description) {
    }

    private record RoleSeed(String code, String name, String description, List<String> permissions) {
    }
}
