package dev.nexo.erp.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import dev.nexo.erp.security.*;

class AccountMfaQrTests {
    @Test void refusesQrWithoutAnAlreadyVerifiedMfaSession() {
        var mfa=mock(MfaService.class);
        var controller=new AccountController(mock(AccountSecurityService.class),mfa,new MfaQrCode());
        var response=new MockHttpServletResponse();
        assertThat(controller.authenticator("password",()->"admin",new MockHttpServletRequest(),response,
            new ExtendedModelMap(),new RedirectAttributesModelMap())).isEqualTo("redirect:/seguridad/mfa");
        verifyNoInteractions(mfa);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }
    @Test void revealsOnlyOwnQrAfterPasswordConfirmationAndDoesNotRotateSecret() {
        var mfa=mock(MfaService.class);
        when(mfa.confirmedProvisioningUri(eq("admin"),eq("password"),anyString()))
            .thenReturn("otpauth://totp/Nexo:admin?secret=GEZDGNBVGY3TQOJQ&issuer=Nexo");
        var controller=new AccountController(mock(AccountSecurityService.class),mfa,new MfaQrCode());
        var request=new MockHttpServletRequest();
        request.getSession().setAttribute(MfaSession.VERIFIED,true);
        var model=new ExtendedModelMap();
        assertThat(controller.authenticator("password",()->"admin",request,new MockHttpServletResponse(),model,
            new RedirectAttributesModelMap())).isEqualTo("mfa-link");
        assertThat((String)model.get("mfaQr")).startsWith("data:image/png;base64,");
        assertThat(model).doesNotContainKeys("secret","currentPassword");
        verify(mfa).confirmedProvisioningUri(eq("admin"),eq("password"),anyString());
        verifyNoMoreInteractions(mfa);
    }
    @Test void wrongPasswordNeverShowsQr() {
        var mfa=mock(MfaService.class);
        when(mfa.confirmedProvisioningUri(anyString(),anyString(),anyString()))
            .thenThrow(new IllegalArgumentException("Clave incorrecta"));
        var controller=new AccountController(mock(AccountSecurityService.class),mfa,new MfaQrCode());
        var request=new MockHttpServletRequest();
        request.getSession().setAttribute(MfaSession.VERIFIED,true);
        var model=new ExtendedModelMap();
        assertThat(controller.authenticator("wrong",()->"admin",request,new MockHttpServletResponse(),model,
            new RedirectAttributesModelMap())).isEqualTo("redirect:/mi-cuenta");
        assertThat(model).doesNotContainKey("mfaQr");
    }
}
