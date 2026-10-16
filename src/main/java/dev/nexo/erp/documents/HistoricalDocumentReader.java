package dev.nexo.erp.documents;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

@Component
public class HistoricalDocumentReader {

    private static final long MAX_XML_BYTES = 10 * 1024 * 1024;

    public HistoricalDocument read(Path path) {
        String fileName = path.getFileName().toString();
        try {
            long size = Files.size(path);
            if (size == 0) {
                return invalid(fileName, HistoricalDocument.ValidationState.EMPTY, "El archivo está vacío");
            }
            if (size > MAX_XML_BYTES) {
                return invalid(fileName, HistoricalDocument.ValidationState.INVALID, "Supera el límite seguro de 10 MB");
            }

            DocumentBuilderFactory factory = secureFactory();
            Document document;
            try (InputStream input = Files.newInputStream(path)) {
                document = factory.newDocumentBuilder().parse(input);
            }

            XPath xpath = XPathFactory.newInstance().newXPath();
            String rootName = document.getDocumentElement().getLocalName();
            String type = "CreditNote".equals(rootName) ? "Nota de crédito" : "Factura";
            String number = value(xpath, document, "/*/*[local-name()='ID'][1]");
            String issueDate = value(xpath, document, "/*/*[local-name()='IssueDate'][1]");
            String customerDocument = value(xpath, document,
                "/*/*[local-name()='AccountingCustomerParty']/*[local-name()='Party']" +
                "/*[local-name()='PartyIdentification']/*[local-name()='ID'][1]");
            String customerName = value(xpath, document,
                "/*/*[local-name()='AccountingCustomerParty']/*[local-name()='Party']" +
                "/*[local-name()='PartyLegalEntity']/*[local-name()='RegistrationName'][1]");
            String currency = value(xpath, document, "/*/*[local-name()='DocumentCurrencyCode'][1]");
            String payable = value(xpath, document,
                "/*/*[local-name()='LegalMonetaryTotal' or local-name()='RequestedMonetaryTotal']" +
                "/*[local-name()='PayableAmount'][1]");
            boolean signed = (Boolean) xpath.evaluate(
                "boolean(//*[local-name()='SignatureValue'])", document, XPathConstants.BOOLEAN);

            return new HistoricalDocument(
                type,
                fallback(number, "Sin número"),
                parseDate(issueDate),
                fallback(customerDocument, "Sin documento"),
                fallback(customerName, "Cliente no identificado"),
                fallback(currency, "PEN"),
                parseAmount(payable),
                fileName,
                sha256(path),
                signed,
                HistoricalDocument.ValidationState.READABLE,
                signed ? "Estructura legible y firma incluida" : "Estructura legible; firma no localizada"
            );
        } catch (Exception exception) {
            return invalid(fileName, HistoricalDocument.ValidationState.INVALID, "No se pudo interpretar el XML");
        }
    }

    private DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private String value(XPath xpath, Document document, String expression) throws Exception {
        return xpath.evaluate(expression, document).strip();
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal parseAmount(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStreamSink.INSTANCE);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private HistoricalDocument invalid(
        String fileName,
        HistoricalDocument.ValidationState state,
        String observation
    ) {
        String type = fileName.toUpperCase().contains("NOTA_CREDITO") ? "Nota de crédito" : "Factura";
        return new HistoricalDocument(
            type, "Sin número", null, "—", "Documento por revisar", "—", null,
            fileName, "—", false, state, observation
        );
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int value) {
            // Sink used only to stream bytes through the digest without retaining document content.
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            // Intentionally empty.
        }
    }
}
