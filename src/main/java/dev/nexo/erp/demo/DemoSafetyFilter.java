package dev.nexo.erp.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class DemoSafetyFilter extends OncePerRequestFilter {
    private static final Set<String> SESSION_ROUTES = Set.of("/login", "/logout");
    private final boolean readOnly;
    public DemoSafetyFilter(@Value("${app.demo.read-only:true}") boolean readOnly) {
        this.readOnly = readOnly;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        boolean sensitive = path.startsWith("/modulos/configuracion") || path.startsWith("/mi-cuenta")
            || path.startsWith("/seguridad/") || path.startsWith("/modulos/contexto")
            || path.equals("/modulos/comprobantes");
        boolean read = Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        boolean session = "POST".equals(request.getMethod()) && SESSION_ROUTES.contains(path);
        if (readOnly && (sensitive || (!read && !session))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Demo publica de solo lectura. No ingreses datos personales ni comerciales reales.");
            return;
        }
        chain.doFilter(request, response);
    }
}
