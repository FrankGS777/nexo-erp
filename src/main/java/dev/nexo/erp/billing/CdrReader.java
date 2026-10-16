package dev.nexo.erp.billing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Component
public class CdrReader {

    private static final int MAX_CDR_BYTES = 5 * 1024 * 1024;
    private static final String APP_RESPONSE = "urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2";
    private static final String AGGREGATE = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String BASIC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    public Response read(byte[] content, boolean zip, String expectedReference) {
        if (content == null || content.length == 0 || content.length > MAX_CDR_BYTES) {
            throw new IllegalArgumentException("El CDR está vacío o supera el límite de 5 MB");
        }
        byte[] xml = zip ? xmlFromZip(content) : content;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            if (!APP_RESPONSE.equals(root.getNamespaceURI()) || !"ApplicationResponse".equals(root.getLocalName())) {
                throw new IllegalArgumentException("El archivo no es una respuesta CDR UBL de SUNAT");
            }
            Element documentResponse = child(root, AGGREGATE, "DocumentResponse");
            Element response = documentResponse == null ? null : child(documentResponse, AGGREGATE, "Response");
            String code = text(response, BASIC, "ResponseCode");
            String description = text(response, BASIC, "Description");
            String reference = text(response, BASIC, "ReferenceID");
            if (code == null || description == null || reference == null) {
                throw new IllegalArgumentException("El XML no contiene una respuesta CDR reconocible");
            }
            if (!expectedReference.equalsIgnoreCase(reference.strip())) {
                throw new IllegalArgumentException("El CDR corresponde a " + reference.strip()
                    + " y no al comprobante " + expectedReference);
            }
            List<String> observations = new ArrayList<>();
            var notes = root.getElementsByTagNameNS(BASIC, "Note");
            for (int index = 0; index < notes.getLength(); index++) {
                String note = notes.item(index).getTextContent();
                if (note != null && !note.isBlank()) observations.add(note.strip());
            }
            return new Response(code.strip(), description.strip(), reference.strip(),
                List.copyOf(observations), xml);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("El CDR no es un XML seguro o válido", exception);
        }
    }

    public boolean looksLikeZip(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K';
    }

    private byte[] xmlFromZip(byte[] archive) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 20) {
                    throw new IllegalArgumentException("El ZIP CDR contiene demasiados archivos");
                }
                String entryName = entry.getName().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && entryName.endsWith(".xml")) {
                    byte[] xml = zip.readNBytes(MAX_CDR_BYTES + 1);
                    if (xml.length > MAX_CDR_BYTES) {
                        throw new IllegalArgumentException("El XML del CDR supera el límite permitido");
                    }
                    return xml;
                }
            }
            throw new IllegalArgumentException("El ZIP no contiene un XML de respuesta");
        } catch (IOException exception) {
            throw new IllegalArgumentException("El ZIP CDR no es válido", exception);
        }
    }

    private Element child(Element parent, String namespace, String localName) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private String text(Element parent, String namespace, String localName) {
        Element element = child(parent, namespace, localName);
        return element == null ? null : element.getTextContent();
    }

    public record Response(String code, String description, String reference,
                           List<String> observations, byte[] xml) { }
}
