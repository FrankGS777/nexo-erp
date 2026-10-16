package dev.nexo.erp.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CdrReaderTests {

    private final CdrReader reader = new CdrReader();

    @Test
    void acceptsOnlyTheExpectedUblResponse() {
        var response = reader.read(cdr("E001-7"), false, "E001-7");
        assertThat(response.code()).isEqualTo("0");
        assertThat(response.reference()).isEqualTo("E001-7");
        assertThatThrownBy(() -> reader.read(cdr("E001-8"), false, "E001-7"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no al comprobante");
    }

    @Test
    void rejectsLookalikeXmlAndExternalEntities() {
        byte[] lookalike = """
            <Invoice xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:ReferenceID>E001-7</cbc:ReferenceID><cbc:ResponseCode>0</cbc:ResponseCode>
              <cbc:Description>Aceptado</cbc:Description>
            </Invoice>
            """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(lookalike, false, "E001-7"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CDR UBL");

        byte[] external = """
            <!DOCTYPE ApplicationResponse [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <ApplicationResponse>&leak;</ApplicationResponse>
            """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(external, false, "E001-7"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] cdr(String reference) {
        return ("""
            <ApplicationResponse xmlns="urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2"
              xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
              xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cac:DocumentResponse><cac:Response>
                <cbc:ReferenceID>%s</cbc:ReferenceID>
                <cbc:ResponseCode>0</cbc:ResponseCode>
                <cbc:Description>Aceptado</cbc:Description>
              </cac:Response></cac:DocumentResponse>
            </ApplicationResponse>
            """).formatted(reference).getBytes(StandardCharsets.UTF_8);
    }
}
