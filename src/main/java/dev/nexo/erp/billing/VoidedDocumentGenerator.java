package dev.nexo.erp.billing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

@Component
public class VoidedDocumentGenerator {

    private static final String ROOT = "urn:sunat:names:specification:ubl:peru:schema:xsd:VoidedDocuments-1";
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
            writer.writeStartElement(ROOT, "VoidedDocuments");
            writer.writeDefaultNamespace(ROOT);
            writer.writeNamespace("cac", CAC);
            writer.writeNamespace("cbc", CBC);
            writer.writeNamespace("sac", SAC);
            element(writer, CBC, "UBLVersionID", "2.0");
            element(writer, CBC, "CustomizationID", "1.0");
            element(writer, CBC, "ID", data.identifier());
            element(writer, CBC, "ReferenceDate", data.referenceDate().toString());
            element(writer, CBC, "IssueDate", data.issueDate().toString());
            writer.writeStartElement(CAC, "AccountingSupplierParty");
            attributeElement(writer, CBC, "CustomerAssignedAccountID", data.ruc(), "schemeID", "6");
            element(writer, CBC, "AdditionalAccountID", "6");
            writer.writeStartElement(CAC, "Party");
            writer.writeStartElement(CAC, "PartyLegalEntity");
            element(writer, CBC, "RegistrationName", data.legalName());
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeStartElement(SAC, "VoidedDocumentsLine");
            element(writer, CBC, "LineID", "1");
            element(writer, CBC, "DocumentTypeCode", data.documentType());
            element(writer, SAC, "DocumentSerialID", data.series());
            element(writer, SAC, "DocumentNumberID", String.valueOf(data.number()));
            element(writer, SAC, "VoidReasonDescription", data.reason());
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar la comunicación de baja", exception);
        }
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
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    public record Data(String identifier, LocalDate referenceDate, LocalDate issueDate,
                       String ruc, String legalName, String documentType,
                       String series, long number, String reason) { }
}
