package dev.nexo.erp.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public DatabaseUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        Account account = jdbc.sql("""
                select id, username, password_hash, active, locked_until
                from app_user
                where lower(username) = :username
                """)
            .param("username", normalized)
            .query(Account.class)
            .optional()
            .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        jdbc.sql("""
                select distinct r.code
                from role r
                join user_role ur on ur.role_id = r.id
                where ur.user_id = :userId
                """)
            .param("userId", account.id())
            .query(String.class)
            .list()
            .forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));

        jdbc.sql("""
                select distinct p.code
                from permission p
                join role_permission rp on rp.permission_id = p.id
                join user_role ur on ur.role_id = rp.role_id
                where ur.user_id = :userId
                """)
            .param("userId", account.id())
            .query(String.class)
            .list()
            .forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

        return User.withUsername(account.username())
            .password(account.passwordHash())
            .authorities(authorities)
            .disabled(!account.active())
            .accountLocked(account.lockedUntil() != null && account.lockedUntil().isAfter(OffsetDateTime.now()))
            .build();
    }

    private record Account(
        long id,
        String username,
        String passwordHash,
        boolean active,
        OffsetDateTime lockedUntil
    ) {
    }
}
