package dev.nexo.erp.billing;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

@Component
public class DailySummaryGenerator {

    private static final String ROOT = "urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final String SAC = "urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1";

    public byte[] generate(Data data) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.setDefaultNamespace(ROOT);
            writer.writeStartElement(ROOT, "SummaryDocuments");
            writer.writeDefaultNamespace(ROOT);
            writer.writeNamespace("cac", CAC);
            writer.writeNamespace("cbc", CBC);
            writer.writeNamespace("sac", SAC);
            element(writer, CBC, "UBLVersionID", "2.0");
            element(writer, CBC, "CustomizationID", "1.1");
            element(writer, CBC, "ID", data.identifier());
            element(writer, CBC, "ReferenceDate", data.referenceDate().toString());
            element(writer, CBC, "IssueDate", data.issueDate().toString());
            supplier(writer, data);
            writer.writeStartElement(SAC, "SummaryDocumentsLine");
            element(writer, CBC, "LineID", "1");
            element(writer, CBC, "DocumentTypeCode", data.documentType());
            element(writer, CBC, "ID", data.series() + "-" + data.number());
            customer(writer, data);
            if (data.referenceDocument() != null) {
                writer.writeStartElement(CAC, "BillingReference");
                writer.writeStartElement(CAC, "InvoiceDocumentReference");
                element(writer, CBC, "ID", data.referenceDocument());
                element(writer, CBC, "DocumentTypeCode", data.referenceDocumentType());
                writer.writeEndElement();
                writer.writeEndElement();
            }
            writer.writeStartElement(CAC, "Status");
            element(writer, CBC, "ConditionCode", data.conditionCode());
            writer.writeEndElement();
            amount(writer, SAC, "TotalAmount", data.total(), data.currency());
            writer.writeStartElement(SAC, "BillingPayment");
            amount(writer, CBC, "PaidAmount", data.subtotal(), data.currency());
            element(writer, CBC, "InstructionID", "01");
            writer.writeEndElement();
            writer.writeStartElement(CAC, "TaxTotal");
            amount(writer, CBC, "TaxAmount", data.tax(), data.currency());
            writer.writeStartElement(CAC, "TaxSubtotal");
            amount(writer, CBC, "TaxableAmount", data.subtotal(), data.currency());
            amount(writer, CBC, "TaxAmount", data.tax(), data.currency());
            writer.writeStartElement(CAC, "TaxCategory");
            writer.writeStartElement(CAC, "TaxScheme");
            element(writer, CBC, "ID", "1000");
            element(writer, CBC, "Name", "IGV");
            element(writer, CBC, "TaxTypeCode", "VAT");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el resumen diario", exception);
        }
    }

    private void supplier(XMLStreamWriter writer, Data data) throws Exception {
        writer.writeStartElement(CAC, "AccountingSupplierParty");
        attributeElement(writer, CBC, "CustomerAssignedAccountID", data.ruc(), "schemeID", "6");
        element(writer, CBC, "AdditionalAccountID", "6");
        writer.writeStartElement(CAC, "Party");
        writer.writeStartElement(CAC, "PartyLegalEntity");
        element(writer, CBC, "RegistrationName", data.legalName());
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void customer(XMLStreamWriter writer, Data data) throws Exception {
        writer.writeStartElement(CAC, "AccountingCustomerParty");
        attributeElement(writer, CBC, "CustomerAssignedAccountID", data.customerDocument(),
            "schemeID", data.customerDocumentType());
        element(writer, CBC, "AdditionalAccountID", data.customerDocumentType());
        writer.writeEndElement();
    }

    private void amount(XMLStreamWriter writer, String namespace, String name,
                        BigDecimal value, String currency) throws Exception {
        writer.writeStartElement(namespace, name);
        writer.writeAttribute("currencyID", currency);
        writer.writeCharacters(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
        writer.writeEndElement();
    }

    private void element(XMLStreamWriter writer, String namespace, String name, String value) throws Exception {
        writer.writeStartElement(namespace, name);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    private void attributeElement(XMLStreamWriter writer, String namespace, String name, String value,
                                  String attribute, String attributeValue) throws Exception {
        writer.writeStartElement(namespace, name);
        writer.writeAttribute(attribute, attributeValue);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    public record Data(String identifier, LocalDate referenceDate, LocalDate issueDate,
                       String ruc, String legalName, String documentType, String series,
                       long number, String customerDocumentType, String customerDocument,
                       BigDecimal subtotal, BigDecimal tax, BigDecimal total, String currency,
                       String referenceDocumentType, String referenceDocument,
                       String conditionCode) { }
}
