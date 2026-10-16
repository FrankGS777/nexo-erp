package dev.nexo.erp.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class FiscalXmlGeneratorTests {

    @Test
    void invoiceContainsSunatOperationCurrencyAndAmountLegend() throws Exception {
        byte[] xml = new UblDocumentGenerator().generate(document("01", null, null));
        var document = parse(xml);
        assertThat(text(document, "ProfileID")).isEqualTo("0101");
        assertThat(text(document, "InvoiceTypeCode")).isEqualTo("01");
        assertThat(text(document, "DocumentCurrencyCode")).isEqualTo("PEN");
        assertThat(text(document, "Note")).contains("SON VEINTITRES CON 60/100 SOLES");
        assertThat(text(document, "PayableAmount")).isEqualTo("23.60");
    }

    @Test
    void creditNoteUsesSchemaOrderAndReference() throws Exception {
        byte[] xml = new UblDocumentGenerator().generate(document("07", "01", "E001-1"));
        var root = parse(xml).getDocumentElement();
        int currency = childIndex(root, "DocumentCurrencyCode");
        int discrepancy = childIndex(root, "DiscrepancyResponse");
        int billing = childIndex(root, "BillingReference");
        assertThat(currency).isLessThan(discrepancy);
        assertThat(discrepancy).isLessThan(billing);
    }

    @Test
    void preservesUnitPrecisionWhileTotalsRemainInCents() throws Exception {
        var base = document("01", null, null);
        var data = new UblDocumentGenerator.DocumentData("01", "F001", 2, base.issueDate(),
            base.currency(), base.companyRuc(), base.companyLegalName(), base.companyTradeName(),
            base.companyAddress(), base.companyUbigeo(), base.customerDocumentType(),
            base.customerDocument(), base.customerName(), new BigDecimal("84.75"),
            new BigDecimal("15.25"), new BigDecimal("100.00"), null, null, null, null,
            List.of(new UblDocumentGenerator.LineData(1, "SKU-1", "Producto", "NIU",
                new BigDecimal("100"), new BigDecimal("0.847458"), new BigDecimal("84.75"),
                new BigDecimal("15.25"), new BigDecimal("100.00"))));
        var xml = parse(new UblDocumentGenerator().generate(data));
        var prices = xml.getElementsByTagNameNS("*", "PriceAmount");
        assertThat(prices.item(0).getTextContent()).isEqualTo("1.000000");
        assertThat(prices.item(1).getTextContent()).isEqualTo("0.847458");
        assertThat(new BigDecimal(prices.item(1).getTextContent()).multiply(new BigDecimal("100"))
            .setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("84.75");
        assertThat(text(xml, "LineExtensionAmount")).isEqualTo("84.75");
        assertThat(text(xml, "PayableAmount")).isEqualTo("100.00");
    }

    @Test
    void creditInvoiceContainsDueDatePaymentTermsAndOperationReferences() throws Exception {
        var base = document("01", null, null);
        var data = new UblDocumentGenerator.DocumentData(base.documentType(), base.series(), base.number(),
            base.issueDate(), base.currency(), base.companyRuc(), base.companyLegalName(), base.companyTradeName(),
            base.companyAddress(), base.companyUbigeo(), base.customerDocumentType(), base.customerDocument(),
            base.customerName(), base.subtotal(), base.taxAmount(), base.total(), null, null, null, null,
            base.lines(), "0101", "CREDITO", LocalDate.of(2026, 10, 25), "OC-1042", "T001-88",
            "Av. Cliente 123", "150101", "Almacén Callao", "070101");
        var document = parse(new UblDocumentGenerator().generate(data));
        assertThat(text(document, "ProfileID")).isEqualTo("0101");
        assertThat(text(document, "DueDate")).isEqualTo("2026-10-25");
        assertThat(text(document, "PaymentMeansID")).isEqualTo("Credito");
        assertThat(text(document, "OrderReference")).contains("OC-1042");
        assertThat(text(document, "DespatchDocumentReference")).contains("T001-88");
        // SUNAT row 63/433: a top-level delivery address is exclusive to itinerant sales.
        // Ordinary dispatch addresses stay in the order, commercial PDF and remittance guide.
        assertThat(document.getElementsByTagNameNS("*", "Delivery").getLength()).isZero();
        assertThat(new String(new UblDocumentGenerator().generate(data), java.nio.charset.StandardCharsets.UTF_8))
            .doesNotContain("languageLocaleID=\"2005\"");
    }

    @Test
    void dailySummaryContainsBoletaTotalsAndStatus() throws Exception {
        var data = new DailySummaryGenerator.Data("RC-20260925-1", LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 9, 25), "00000000000", "Nexo ERP", "03", "B001", 1,
            "1", "12345678", new BigDecimal("20.00"), new BigDecimal("3.60"),
            new BigDecimal("23.60"), "PEN", null, null, "1");
        var document = parse(new DailySummaryGenerator().generate(data));
        assertThat(text(document, "ID")).isEqualTo("RC-20260925-1");
        assertThat(text(document, "DocumentTypeCode")).isEqualTo("03");
        assertThat(text(document, "ConditionCode")).isEqualTo("1");
        assertThat(text(document, "TotalAmount")).isEqualTo("23.60");
    }

    private UblDocumentGenerator.DocumentData document(String type, String referenceType,
                                                        String reference) {
        return new UblDocumentGenerator.DocumentData(type, "07".equals(type) ? "EC01" : "E001", 1,
            LocalDate.of(2026, 9, 25), "PEN", "00000000000", "Nexo ERP SAC",
            "Nexo", "Lima", "150101", "6", "00000000102", "Cliente SAC",
            new BigDecimal("20.00"), new BigDecimal("3.60"), new BigDecimal("23.60"),
            referenceType, reference, "01", "Anulación", List.of(
                new UblDocumentGenerator.LineData(1, "SKU-1", "Producto", "NIU",
                    BigDecimal.ONE, new BigDecimal("20.00"), new BigDecimal("20.00"),
                    new BigDecimal("3.60"), new BigDecimal("23.60"))));
    }

    private org.w3c.dom.Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private String text(org.w3c.dom.Document document, String localName) {
        return document.getElementsByTagNameNS("*", localName).item(0).getTextContent();
    }

    private int childIndex(org.w3c.dom.Element root, String localName) {
        int index = 0;
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                if (localName.equals(child.getLocalName())) return index;
                index++;
            }
        }
        return -1;
    }
}
