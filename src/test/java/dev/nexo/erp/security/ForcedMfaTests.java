package dev.nexo.erp.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

class ForcedMfaTests {
    @Test void forcedPolicyRefusesMissingEncryptionKeyAndCannotBeDisabled() {
        var crypto=mock(MfaCryptoService.class);
        assertThatThrownBy(()->new MfaService(mock(JdbcClient.class),mock(TotpService.class),crypto,
            mock(PasswordEncoder.class),true)).isInstanceOf(IllegalStateException.class);
        when(crypto.available()).thenReturn(true);
        var service=new MfaService(mock(JdbcClient.class),mock(TotpService.class),crypto,mock(PasswordEncoder.class),true);
        assertThatThrownBy(()->service.setRequired(1,false,"admin")).isInstanceOf(IllegalArgumentException.class);
    }
}
