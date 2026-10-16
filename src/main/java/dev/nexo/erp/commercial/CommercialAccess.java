package dev.nexo.erp.commercial;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CommercialAccess {
    private final JdbcClient jdbc;
    public CommercialAccess(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long userId(String username) {
        return jdbc.sql("select id from app_user where username=:name and active=true")
            .param("name", username).query(Long.class).optional()
            .orElseThrow(() -> new AccessDeniedException("Usuario no autorizado"));
    }
    public boolean has(String username, String permission) {
        return jdbc.sql("""
            select count(*) from app_user u join user_role ur on ur.user_id=u.id
            join role_permission rp on rp.role_id=ur.role_id join permission p on p.id=rp.permission_id
            where u.username=:name and u.active=true and p.code=:permission
            """).param("name", username).param("permission", permission).query(Long.class).single() > 0;
    }
    public void require(String username, String permission) {
        if (!has(username, permission)) throw new AccessDeniedException("No tienes permiso para esta operacion");
    }
    public boolean allCustomers(String username) { return has(username, "PORTFOLIO_ALL"); }
    public void customer(long customerId, String username) {
        if (allCustomers(username)) return;
        if (jdbc.sql("select count(*) from customer where id=:id and assigned_seller_id=:user")
            .param("id", customerId).param("user", userId(username)).query(Long.class).single() == 0)
            throw new AccessDeniedException("Cliente fuera de tu cartera");
    }
    public String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()) ? null : auth.getName();
    }
}
