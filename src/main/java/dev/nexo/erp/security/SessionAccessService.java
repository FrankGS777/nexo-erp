package dev.nexo.erp.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SessionAccessService {
    private static final String VERSION="NEXO_SECURITY_VERSION";
    private final JdbcClient jdbc;
    public SessionAccessService(JdbcClient jdbc) {this.jdbc=jdbc;}
    public boolean validate(String username,HttpSession session) {
        var account=jdbc.sql("select active,security_version from app_user where username=:username")
            .param("username",username).query().listOfRows();
        if(account.isEmpty()||!Boolean.TRUE.equals(account.getFirst().get("active")))return false;
        long current=((Number)account.getFirst().get("security_version")).longValue();
        Object previous=session.getAttribute(VERSION);
        if(previous==null)session.setAttribute(VERSION,current);
        return previous==null||previous.equals(current);
    }
}
