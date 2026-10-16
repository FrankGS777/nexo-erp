package dev.nexo.erp.billing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.naming.ldap.LdapName;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
public class XmlDigitalSigner {

    private static final String EXT = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    public byte[] sign(byte[] unsignedXml, byte[] pkcs12, char[] password,
                       String expectedRuc, String legalName) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(pkcs12), password);
            String alias = null;
            for (String candidate : Collections.list(store.aliases())) {
                if (store.isKeyEntry(candidate)) {
                    alias = candidate;
                    break;
                }
            }
            if (alias == null) {
                throw new IllegalArgumentException("El PFX/P12 no contiene una clave privada");
            }
            PrivateKey privateKey = (PrivateKey) store.getKey(alias, password);
            X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
            if (privateKey == null || certificate == null) {
                throw new IllegalArgumentException("El PFX/P12 no contiene clave y certificado utilizables");
            }
            certificate.checkValidity();
            validateCertificateRuc(certificate, expectedRuc);

            Document document = parse(unsignedXml);
            Element root = document.getDocumentElement();
            Element extensions = document.createElementNS(EXT, "ext:UBLExtensions");
            Element extension = document.createElementNS(EXT, "ext:UBLExtension");
            Element content = document.createElementNS(EXT, "ext:ExtensionContent");
            extension.appendChild(content);
            extensions.appendChild(extension);
            root.insertBefore(extensions, root.getFirstChild());
            insertBusinessSignature(document, root, expectedRuc, legalName);

            // Normalize namespace placement before calculating the digest. Serializers may move
            // inherited namespace declarations; doing that first keeps the signed octets stable.
            document = parse(serialize(document));
            content = (Element) document.getElementsByTagNameNS(EXT, "ExtensionContent").item(0);

            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Transform enveloped = factory.newTransform(Transform.ENVELOPED,
                (javax.xml.crypto.dsig.spec.TransformParameterSpec) null);
            Transform canonical = factory.newTransform(CanonicalizationMethod.INCLUSIVE,
                (javax.xml.crypto.dsig.spec.TransformParameterSpec) null);
            Reference reference = factory.newReference("",
                factory.newDigestMethod(DigestMethod.SHA256, null), List.of(enveloped, canonical), null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,
                    (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(reference));
            KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
            var x509Data = keyInfoFactory.newX509Data(List.of(certificate));
            var keyInfo = keyInfoFactory.newKeyInfo(List.of(x509Data));
            DOMSignContext context = new DOMSignContext(privateKey, content);
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(signedInfo, keyInfo, null, "signatureKG", null)
                .sign(context);
            return serialize(document);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("No se pudo firmar el XML con el certificado configurado", exception);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void validateCertificateRuc(X509Certificate certificate, String expectedRuc) throws Exception {
        boolean matches = new LdapName(certificate.getSubjectX500Principal().getName()).getRdns().stream()
            .filter(rdn -> "OU".equalsIgnoreCase(rdn.getType()))
            .map(rdn -> String.valueOf(rdn.getValue()).replaceAll("\\D", ""))
            .anyMatch(value -> value.contains(expectedRuc));
        if (!matches) {
            throw new IllegalArgumentException("El certificado no identifica el RUC " + expectedRuc + " en su OU");
        }
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private void insertBusinessSignature(Document document, Element root, String ruc, String legalName) {
        Element signature = element(document, CAC, "cac:Signature");
        appendText(document, signature, CBC, "cbc:ID", "IDSignKG");
        Element party = element(document, CAC, "cac:SignatoryParty");
        Element identification = element(document, CAC, "cac:PartyIdentification");
        appendText(document, identification, CBC, "cbc:ID", ruc);
        party.appendChild(identification);
        Element partyName = element(document, CAC, "cac:PartyName");
        appendText(document, partyName, CBC, "cbc:Name", legalName);
        party.appendChild(partyName);
        signature.appendChild(party);
        Element attachment = element(document, CAC, "cac:DigitalSignatureAttachment");
        Element external = element(document, CAC, "cac:ExternalReference");
        appendText(document, external, CBC, "cbc:URI", "#signatureKG");
        attachment.appendChild(external);
        signature.appendChild(attachment);
        var supplier = root.getElementsByTagNameNS(CAC, "AccountingSupplierParty");
        root.insertBefore(signature, supplier.getLength() == 0 ? null : supplier.item(0));
    }

    private Element element(Document document, String namespace, String qualifiedName) {
        return document.createElementNS(namespace, qualifiedName);
    }

    private void appendText(Document document, Element parent, String namespace,
                            String qualifiedName, String value) {
        Element child = element(document, namespace, qualifiedName);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private byte[] serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }
}
