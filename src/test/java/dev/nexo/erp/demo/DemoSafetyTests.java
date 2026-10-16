package dev.nexo.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import dev.nexo.erp.billing.DemoFiscalGateway;
import dev.nexo.erp.billing.FiscalGateway;
import dev.nexo.erp.catalog.IdentityLookupService;

class DemoSafetyTests {
    @Test void publicEditionCannotContactAnyFiscalProvider() {
        var gateway = new DemoFiscalGateway();
        var document = new FiscalGateway.DocumentReference(1,"01","F001",1);
        assertThat(gateway.submit(document,null).code()).isEqualTo("DEMO_NO_FISCAL_CONNECTION");
        assertThat(gateway.submitSummary(null,null).status()).isEqualTo(FiscalGateway.SubmissionResult.Status.NOT_CONFIGURED);
        assertThat(gateway.poll("ticket","reference").cdrArchive()).isNull();
        assertThat(gateway.reconcile(document).signedXml()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new IdentityLookupService().lookup("6","00000000101","viewer"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void demoRejectsMutationsAndSecurityAdministrationEvenWithAValidSession() throws Exception {
        for(String path:new String[]{"/modulos/clientes","/modulos/ventas","/modulos/comprobantes/emitidos/1/enviar","/modulos/contexto/identidad"}) {
            var request=new MockHttpServletRequest("POST",path);request.setServletPath(path);
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();
            new DemoSafetyFilter(true).doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(403);assertThat(chain.getRequest()).isNull();
        }
        for(String path:new String[]{"/modulos/configuracion","/mi-cuenta","/seguridad/mfa","/modulos/comprobantes"}) {
            var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);
            var response=new MockHttpServletResponse();
            new DemoSafetyFilter(true).doFilter(request,response,new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
    @Test void ordinaryViewsAndCsrfProtectedSessionRoutesStillReachSecurityFilters() throws Exception {
        for(String path:new String[]{"/login","/logout","/modulos/clientes","/css/app.css"}) {
            var request=new MockHttpServletRequest(path.equals("/logout")?"POST":"GET",path);request.setServletPath(path);
            var chain=new MockFilterChain();
            new DemoSafetyFilter(true).doFilter(request,new MockHttpServletResponse(),chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
    }
    @Test void sensitivePathsAreDecodedBeforePolicyChecks() throws Exception {
        for(String path:new String[]{"/%6dodulos/configuracion","/modulos/%63onfiguracion","/%6di-cuenta"}) {
            var request=new MockHttpServletRequest("GET",path);
            var response=new MockHttpServletResponse();
            new DemoSafetyFilter(true).doFilter(request,response,new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
}
