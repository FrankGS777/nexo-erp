package dev.nexo.erp.security;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class MfaProvisioningTests {
    JdbcClient jdbc;
    MfaService mfa;
    String encrypted;
    @BeforeEach void setup() {
        jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.sql("create table app_user(id bigint primary key, username varchar, active boolean, must_change_password boolean, password_hash varchar, mfa_required boolean, mfa_secret_encrypted varchar, mfa_locked_until timestamp with time zone, mfa_failed_attempts int default 0, updated_at timestamp with time zone)").update();
        jdbc.sql("create table audit_event(actor_user_id bigint, action varchar, resource_type varchar, resource_id varchar, outcome varchar, source_ip varchar, correlation_id varchar, details varchar)").update();
        var crypto=new MfaCryptoService("clave-maestra-aislada-de-prueba-nexo");
        var encoder=new BCryptPasswordEncoder(4);
        encrypted=crypto.encrypt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        jdbc.sql("insert into app_user(id,username,active,must_change_password,password_hash,mfa_required,mfa_secret_encrypted) values(1,'admin',true,false,:hash,true,:secret)")
            .param("hash",encoder.encode("PruebaUnica2026")).param("secret",encrypted).update();
        mfa=new MfaService(jdbc,new TotpService(),crypto,encoder,false);
    }
    @Test void correctPasswordExportsExistingSecretWithoutChangingIt() {
        assertThat(mfa.confirmedProvisioningUri("admin","PruebaUnica2026","127.0.0.1"))
            .contains("otpauth://totp/","secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(jdbc.sql("select mfa_secret_encrypted from app_user where id=1").query(String.class).single()).isEqualTo(encrypted);
        assertThat(jdbc.sql("select details from audit_event").query(String.class).single())
            .doesNotContain("GEZDGNBV","PruebaUnica2026","otpauth");
    }
    @Test void wrongPasswordsAreRateLimitedAndNeverExportTheSecret() {
        for(int i=0;i<5;i++) assertThatThrownBy(()->mfa.confirmedProvisioningUri("admin","incorrecta","127.0.0.1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->mfa.confirmedProvisioningUri("admin","PruebaUnica2026","127.0.0.1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bloqueo");
        assertThat(jdbc.sql("select mfa_failed_attempts from app_user where id=1").query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select count(*) from audit_event where action='MFA_PROVISIONING_VIEW'").query(Integer.class).single()).isZero();
    }
}
