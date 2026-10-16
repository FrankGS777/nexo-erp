package dev.nexo.erp.billing;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

@Component
public class UblDocumentGenerator {

    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    public byte[] generate(DocumentData data) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            String rootNamespace = rootNamespace(data.documentType());
            String root = rootElement(data.documentType());
            writer.setDefaultNamespace(rootNamespace);
            writer.writeStartElement(rootNamespace, root);
            writer.writeDefaultNamespace(rootNamespace);
            writer.writeNamespace("cac", CAC);
            writer.writeNamespace("cbc", CBC);
            element(writer, CBC, "UBLVersionID", "2.1");
            sunatCustomization(writer);
            if ("01".equals(data.documentType()) || "03".equals(data.documentType())) {
                operationProfile(writer, data.operationType());
            }
            element(writer, CBC, "ID", data.series() + "-" + data.number());
            element(writer, CBC, "IssueDate", data.issueDate().toString());
            if (data.dueDate() != null && ("01".equals(data.documentType()) || "03".equals(data.documentType()))) {
                element(writer, CBC, "DueDate", data.dueDate().toString());
            }
            if ("01".equals(data.documentType()) || "03".equals(data.documentType())) {
                invoiceType(writer, data.documentType(), data.operationType());
                elementWithAttribute(writer, CBC, "Note",
                    SpanishAmountInWords.convert(data.total(), data.currency()),
                    "languageLocaleID", "1000");
                currency(writer, data.currency());
            } else {
                currency(writer, data.currency());
                discrepancy(writer, data);
                billingReference(writer, data);
            }
            if (isInvoice(data)) {
                references(writer, data);
            }
            supplier(writer, data);
            customer(writer, data);
            if (isInvoice(data)) {
                // SUNAT reserves top-level Delivery/AddressLine for itinerant sales.
                // Ordinary dispatch addresses belong to the commercial order and guide.
                paymentTerms(writer, data);
            }
            taxTotal(writer, data);
            monetaryTotal(writer, data);
            for (LineData line : data.lines()) {
                line(writer, data, line);
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toByteArray();
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("No se pudo construir el XML UBL", exception);
        }
    }

    private void supplier(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        writer.writeStartElement(CAC, "AccountingSupplierParty");
        writer.writeStartElement(CAC, "Party");
        writer.writeStartElement(CAC, "PartyIdentification");
        documentIdentity(writer, data.companyRuc(), "6");
        writer.writeEndElement();
        writer.writeStartElement(CAC, "PartyName");
        element(writer, CBC, "Name", data.companyTradeName());
        writer.writeEndElement();
        writer.writeStartElement(CAC, "PartyLegalEntity");
        element(writer, CBC, "RegistrationName", data.companyLegalName());
        writer.writeStartElement(CAC, "RegistrationAddress");
        element(writer, CBC, "ID", data.companyUbigeo());
        element(writer, CBC, "AddressTypeCode", "0000");
        writer.writeStartElement(CAC, "AddressLine");
        element(writer, CBC, "Line", data.companyAddress());
        writer.writeEndElement();
        writer.writeStartElement(CAC, "Country");
        element(writer, CBC, "IdentificationCode", "PE");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void customer(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        writer.writeStartElement(CAC, "AccountingCustomerParty");
        writer.writeStartElement(CAC, "Party");
        writer.writeStartElement(CAC, "PartyIdentification");
        documentIdentity(writer, data.customerDocument(), data.customerDocumentType());
        writer.writeEndElement();
        writer.writeStartElement(CAC, "PartyLegalEntity");
        element(writer, CBC, "RegistrationName", data.customerName());
        if (notBlank(data.customerAddress())) {
            writer.writeStartElement(CAC, "RegistrationAddress");
            if (notBlank(data.customerUbigeo())) element(writer, CBC, "ID", data.customerUbigeo());
            writer.writeStartElement(CAC, "AddressLine");
            element(writer, CBC, "Line", data.customerAddress());
            writer.writeEndElement();
            writer.writeStartElement(CAC, "Country");
            element(writer, CBC, "IdentificationCode", "PE");
            writer.writeEndElement();
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void taxTotal(XMLStreamWriter writer, DocumentData data)
        throws XMLStreamException {
        writer.writeStartElement(CAC, "TaxTotal");
        amount(writer, "TaxAmount", data.taxAmount(), data.currency());
        data.lines().stream().collect(java.util.stream.Collectors.groupingBy(
            line -> line.igvAffectationCode() + "|" + line.taxRate(), java.util.LinkedHashMap::new,
            java.util.stream.Collectors.toList())).values().forEach(group -> {
                try {
                    LineData sample = group.getFirst();
                    BigDecimal taxable = group.stream().map(LineData::lineValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal tax = group.stream().map(LineData::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    writer.writeStartElement(CAC, "TaxSubtotal");
                    amount(writer, "TaxableAmount", taxable, data.currency());
                    amount(writer, "TaxAmount", tax, data.currency());
                    taxCategory(writer, sample.igvAffectationCode(), sample.taxRate());
                    writer.writeEndElement();
                } catch (XMLStreamException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        writer.writeEndElement();
    }

    private void lineTaxTotal(XMLStreamWriter writer, DocumentData data, LineData line) throws XMLStreamException {
        writer.writeStartElement(CAC, "TaxTotal");
        amount(writer, "TaxAmount", line.taxAmount(), data.currency());
        writer.writeStartElement(CAC, "TaxSubtotal");
        amount(writer, "TaxableAmount", line.lineValue(), data.currency());
        amount(writer, "TaxAmount", line.taxAmount(), data.currency());
        taxCategory(writer, line.igvAffectationCode(), line.taxRate());
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void monetaryTotal(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        String element = "08".equals(data.documentType()) ? "RequestedMonetaryTotal" : "LegalMonetaryTotal";
        writer.writeStartElement(CAC, element);
        amount(writer, "LineExtensionAmount", data.subtotal(), data.currency());
        amount(writer, "TaxInclusiveAmount", data.total(), data.currency());
        amount(writer, "PayableAmount", data.total(), data.currency());
        writer.writeEndElement();
    }

    private void line(XMLStreamWriter writer, DocumentData data, LineData line) throws XMLStreamException {
        String lineElement = switch (data.documentType()) {
            case "07" -> "CreditNoteLine";
            case "08" -> "DebitNoteLine";
            default -> "InvoiceLine";
        };
        writer.writeStartElement(CAC, lineElement);
        element(writer, CBC, "ID", String.valueOf(line.number()));
        String quantityElement = switch (data.documentType()) {
            case "07" -> "CreditedQuantity";
            case "08" -> "DebitedQuantity";
            default -> "InvoicedQuantity";
        };
        elementWithAttribute(writer, CBC, quantityElement, line.quantity().toPlainString(), "unitCode", line.unitCode());
        amount(writer, "LineExtensionAmount", line.lineValue(), data.currency());
        writer.writeStartElement(CAC, "PricingReference");
        writer.writeStartElement(CAC, "AlternativeConditionPrice");
        unitPrice(writer, line.lineTotal().divide(line.quantity(), 6, java.math.RoundingMode.HALF_UP), data.currency());
        priceTypeCode(writer);
        writer.writeEndElement();
        writer.writeEndElement();
        lineTaxTotal(writer, data, line);
        writer.writeStartElement(CAC, "Item");
        element(writer, CBC, "Description", line.description());
        writer.writeStartElement(CAC, "SellersItemIdentification");
        element(writer, CBC, "ID", line.sku());
        writer.writeEndElement();
        if (notBlank(line.sunatProductCode())) {
            writer.writeStartElement(CAC, "CommodityClassification");
            elementWithAttribute(writer, CBC, "ItemClassificationCode", line.sunatProductCode(), "listID", "UNSPSC");
            writer.writeEndElement();
        }
        writer.writeStartElement(CAC, "ClassifiedTaxCategory");
        taxCategoryId(writer, line.igvAffectationCode());
        element(writer, CBC, "Percent", line.taxRate().toPlainString());
        writer.writeStartElement(CAC, "TaxScheme");
        taxScheme(writer, line.igvAffectationCode());
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeStartElement(CAC, "Price");
        unitPrice(writer, line.unitValue(), data.currency());
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void billingReference(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        writer.writeStartElement(CAC, "BillingReference");
        writer.writeStartElement(CAC, "InvoiceDocumentReference");
        element(writer, CBC, "ID", data.referenceDocument());
        element(writer, CBC, "DocumentTypeCode", data.referenceDocumentType());
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void discrepancy(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        writer.writeStartElement(CAC, "DiscrepancyResponse");
        element(writer, CBC, "ReferenceID", data.referenceDocument());
        element(writer, CBC, "ResponseCode", data.reasonCode());
        element(writer, CBC, "Description", data.reason());
        writer.writeEndElement();
    }

    private void sunatCustomization(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(CBC, "CustomizationID");
        writer.writeAttribute("schemeAgencyName", "PE:SUNAT");
        writer.writeCharacters("2.0");
        writer.writeEndElement();
    }

    private void operationProfile(XMLStreamWriter writer, String operationType) throws XMLStreamException {
        writer.writeStartElement(CBC, "ProfileID");
        writer.writeAttribute("schemeName", "Tipo de Operacion");
        writer.writeAttribute("schemeAgencyName", "PE:SUNAT");
        writer.writeAttribute("schemeURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo17");
        writer.writeCharacters(notBlank(operationType) ? operationType : "0101");
        writer.writeEndElement();
    }

    private void invoiceType(XMLStreamWriter writer, String type, String operationType) throws XMLStreamException {
        writer.writeStartElement(CBC, "InvoiceTypeCode");
        writer.writeAttribute("listAgencyName", "PE:SUNAT");
        writer.writeAttribute("listName", "Tipo de Documento");
        writer.writeAttribute("listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo01");
        writer.writeAttribute("name", "Tipo de Operacion");
        writer.writeAttribute("listID", notBlank(operationType) ? operationType : "0101");
        writer.writeCharacters(type);
        writer.writeEndElement();
    }

    private void currency(XMLStreamWriter writer, String currency) throws XMLStreamException {
        writer.writeStartElement(CBC, "DocumentCurrencyCode");
        writer.writeAttribute("listID", "ISO 4217 Alpha");
        writer.writeAttribute("listName", "Currency");
        writer.writeAttribute("listAgencyName", "United Nations Economic Commission for Europe");
        writer.writeCharacters(currency);
        writer.writeEndElement();
    }

    private void documentIdentity(XMLStreamWriter writer, String value, String type) throws XMLStreamException {
        writer.writeStartElement(CBC, "ID");
        writer.writeAttribute("schemeID", type == null ? "0" : type);
        writer.writeAttribute("schemeName", "Documento de Identidad");
        writer.writeAttribute("schemeAgencyName", "PE:SUNAT");
        writer.writeAttribute("schemeURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06");
        writer.writeCharacters(value == null ? "-" : value);
        writer.writeEndElement();
    }

    private void taxCategoryId(XMLStreamWriter writer, String affectationCode) throws XMLStreamException {
        writer.writeStartElement(CBC, "ID");
        writer.writeAttribute("schemeID", "UN/ECE 5305");
        writer.writeAttribute("schemeName", "Tax Category Identifier");
        writer.writeAttribute("schemeAgencyName", "United Nations Economic Commission for Europe");
        writer.writeCharacters(switch (affectationCode) { case "20" -> "E"; case "30" -> "O"; case "40" -> "G"; default -> "S"; });
        writer.writeEndElement();
    }

    private void taxScheme(XMLStreamWriter writer, String affectationCode) throws XMLStreamException {
        writer.writeStartElement(CBC, "ID");
        writer.writeAttribute("schemeID", "UN/ECE 5153");
        writer.writeAttribute("schemeAgencyID", "6");
        String scheme = switch (affectationCode) { case "20" -> "9997"; case "30" -> "9998"; case "40" -> "9995"; default -> "1000"; };
        writer.writeCharacters(scheme);
        writer.writeEndElement();
        element(writer, CBC, "Name", switch (affectationCode) { case "20" -> "EXO"; case "30" -> "INA"; case "40" -> "EXP"; default -> "IGV"; });
        element(writer, CBC, "TaxTypeCode", List.of("30", "40").contains(affectationCode) ? "FRE" : "VAT");
    }

    private void taxExemptionCode(XMLStreamWriter writer, String code) throws XMLStreamException {
        writer.writeStartElement(CBC, "TaxExemptionReasonCode");
        writer.writeAttribute("listAgencyName", "PE:SUNAT");
        writer.writeAttribute("listName", "Afectacion del IGV");
        writer.writeAttribute("listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo07");
        writer.writeCharacters(code);
        writer.writeEndElement();
    }

    private void priceTypeCode(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(CBC, "PriceTypeCode");
        writer.writeAttribute("listName", "Tipo de Precio");
        writer.writeAttribute("listAgencyName", "PE:SUNAT");
        writer.writeAttribute("listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo16");
        writer.writeCharacters("01");
        writer.writeEndElement();
    }

    private void taxCategory(XMLStreamWriter writer, String code, BigDecimal rate) throws XMLStreamException {
        writer.writeStartElement(CAC, "TaxCategory");
        taxCategoryId(writer, code);
        element(writer, CBC, "Percent", rate.toPlainString());
        taxExemptionCode(writer, code);
        writer.writeStartElement(CAC, "TaxScheme");
        taxScheme(writer, code);
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void references(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        if (notBlank(data.purchaseOrderReference())) {
            writer.writeStartElement(CAC, "OrderReference");
            element(writer, CBC, "ID", data.purchaseOrderReference());
            writer.writeEndElement();
        }
        if (notBlank(data.relatedGuideReference())) {
            writer.writeStartElement(CAC, "DespatchDocumentReference");
            element(writer, CBC, "ID", data.relatedGuideReference());
            element(writer, CBC, "DocumentTypeCode", "09");
            writer.writeEndElement();
        }
    }

    private void paymentTerms(XMLStreamWriter writer, DocumentData data) throws XMLStreamException {
        writer.writeStartElement(CAC, "PaymentTerms");
        element(writer, CBC, "ID", "FormaPago");
        element(writer, CBC, "PaymentMeansID", "CREDITO".equals(data.paymentCondition()) ? "Credito" : "Contado");
        if ("CREDITO".equals(data.paymentCondition())) amount(writer, "Amount", data.total(), data.currency());
        writer.writeEndElement();
        if ("CREDITO".equals(data.paymentCondition()) && data.dueDate() != null) {
            writer.writeStartElement(CAC, "PaymentTerms");
            element(writer, CBC, "ID", "FormaPago");
            element(writer, CBC, "PaymentMeansID", "Cuota001");
            amount(writer, "Amount", data.total(), data.currency());
            element(writer, CBC, "PaymentDueDate", data.dueDate().toString());
            writer.writeEndElement();
        }
    }

    private boolean isInvoice(DocumentData data) { return "01".equals(data.documentType()) || "03".equals(data.documentType()); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private void amount(XMLStreamWriter writer, String name, BigDecimal value, String currency)
        throws XMLStreamException {
        elementWithAttribute(writer, CBC, name, value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            "currencyID", currency);
    }

    private void unitPrice(XMLStreamWriter writer, BigDecimal value, String currency) throws XMLStreamException {
        elementWithAttribute(writer, CBC, "PriceAmount",
            value.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString(), "currencyID", currency);
    }

    private void element(XMLStreamWriter writer, String namespace, String name, String value)
        throws XMLStreamException {
        writer.writeStartElement(namespace, name);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    private void elementWithAttribute(XMLStreamWriter writer, String namespace, String name, String value,
                                      String attribute, String attributeValue) throws XMLStreamException {
        writer.writeStartElement(namespace, name);
        writer.writeAttribute(attribute, attributeValue == null ? "" : attributeValue);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    private String rootElement(String type) {
        return switch (type) {
            case "07" -> "CreditNote";
            case "08" -> "DebitNote";
            default -> "Invoice";
        };
    }

    private String rootNamespace(String type) {
        return switch (type) {
            case "07" -> "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
            case "08" -> "urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2";
            default -> "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
        };
    }

    public record DocumentData(String documentType, String series, long number, LocalDate issueDate,
                               String currency, String companyRuc, String companyLegalName,
                               String companyTradeName, String companyAddress, String companyUbigeo,
                               String customerDocumentType, String customerDocument, String customerName,
                               BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
                               String referenceDocumentType, String referenceDocument,
                               String reasonCode, String reason, List<LineData> lines,
                               String operationType, String paymentCondition, LocalDate dueDate,
                               String purchaseOrderReference, String relatedGuideReference,
                               String customerAddress, String customerUbigeo,
                               String deliveryAddress, String deliveryUbigeo) {
        public DocumentData(String documentType, String series, long number, LocalDate issueDate,
                            String currency, String companyRuc, String companyLegalName,
                            String companyTradeName, String companyAddress, String companyUbigeo,
                            String customerDocumentType, String customerDocument, String customerName,
                            BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
                            String referenceDocumentType, String referenceDocument,
                            String reasonCode, String reason, List<LineData> lines) {
            this(documentType, series, number, issueDate, currency, companyRuc, companyLegalName,
                companyTradeName, companyAddress, companyUbigeo, customerDocumentType, customerDocument,
                customerName, subtotal, taxAmount, total, referenceDocumentType, referenceDocument,
                reasonCode, reason, lines, "0101", "CONTADO", null, null, null, null, null, null, null);
        }
    }
    public record LineData(int number, String sku, String description, String unitCode,
                           BigDecimal quantity, BigDecimal unitValue, BigDecimal lineValue,
                           BigDecimal taxAmount, BigDecimal lineTotal, String igvAffectationCode,
                           BigDecimal taxRate, String sunatProductCode) {
        public LineData(int number, String sku, String description, String unitCode,
                        BigDecimal quantity, BigDecimal unitValue, BigDecimal lineValue,
                        BigDecimal taxAmount, BigDecimal lineTotal) {
            this(number, sku, description, unitCode, quantity, unitValue, lineValue, taxAmount,
                lineTotal, "10", new BigDecimal("18"), null);
        }
    }
}
