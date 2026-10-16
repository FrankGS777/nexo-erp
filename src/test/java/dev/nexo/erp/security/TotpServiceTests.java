package dev.nexo.erp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTests {

    private final TotpService totp = new TotpService();

    @Test
    void matchesTheRfc6238Sha1VectorAndRejectsAnInvalidCode() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        Instant instant = Instant.ofEpochSecond(59);

        assertThat(totp.codeAt(secret, instant)).isEqualTo("287082");
        assertThat(totp.verifyAt(secret, "287082", instant).valid()).isTrue();
        assertThat(totp.verifyAt(secret, "000000", instant).valid()).isFalse();
    }

    @Test
    void encryptsTheMfaSecretAndRejectsAWrongMasterKey() {
        MfaCryptoService crypto = new MfaCryptoService(
            "clave-maestra-de-prueba-con-32-caracteres-minimo");
        String secret = "JBSWY3DPEHPK3PXP";

        String encrypted = crypto.encrypt(secret);

        assertThat(encrypted).startsWith("v1:").doesNotContain(secret);
        assertThat(crypto.decrypt(encrypted)).isEqualTo(secret);
        MfaCryptoService wrongKey = new MfaCryptoService(
            "otra-clave-maestra-distinta-con-32-caracteres");
        assertThatThrownBy(() -> wrongKey.decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class);
    }
}
