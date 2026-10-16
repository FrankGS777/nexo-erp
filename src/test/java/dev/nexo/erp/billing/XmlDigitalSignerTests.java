package dev.nexo.erp.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import javax.xml.XMLConstants;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

class XmlDigitalSignerTests {

    @Test
    void signsWholeUblDocumentAndEmbedsCertificate() throws Exception {
        TestCertificate material = certificate("00000000000");
        byte[] signed = new XmlDigitalSigner().sign(invoice(), material.pkcs12(),
            material.password().toCharArray(), "00000000000", "Nexo ERP");

        var document = parse(signed);
        assertThat(document.getElementsByTagNameNS(
            "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2",
            "UBLExtensions").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").getLength()).isEqualTo(1);
        var signatureNode = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        DOMValidateContext context = new DOMValidateContext(new CertificateKeySelector(), signatureNode);
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
        XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
        assertThat(signature.getSignatureValue().validate(context)).as("signature value").isTrue();
        for (Object item : signature.getSignedInfo().getReferences()) {
            var reference = (javax.xml.crypto.dsig.Reference) item;
            assertThat(reference.validate(context)).as("digest for " + reference.getURI()).isTrue();
        }
        assertThat(signature.validate(context)).as("complete XML signature").isTrue();
    }

    @Test
    void refusesCertificateBelongingToAnotherRuc() throws Exception {
        TestCertificate material = certificate("00000000102");
        assertThatThrownBy(() -> new XmlDigitalSigner().sign(invoice(), material.pkcs12(),
            material.password().toCharArray(), "00000000000", "Nexo ERP"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no identifica el RUC");
    }

    private TestCertificate certificate(String ruc) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=Nexo,OU=" + ruc + ",O=Nexo ERP,C=PE");
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(86400)), name, pair.getPublic());
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC").build(pair.getPrivate()));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC")
            .getCertificate(holder);
        String password = "clave-segura-prueba";
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password.toCharArray());
        store.setKeyEntry("nexo", pair.getPrivate(), password.toCharArray(),
            new java.security.cert.Certificate[]{certificate});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.store(output, password.toCharArray());
        return new TestCertificate(output.toByteArray(), password);
    }

    private byte[] invoice() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
              xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
              xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:UBLVersionID>2.1</cbc:UBLVersionID>
              <cbc:ID>E001-1</cbc:ID>
              <cac:AccountingSupplierParty><cac:Party/></cac:AccountingSupplierParty>
            </Invoice>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private org.w3c.dom.Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private record TestCertificate(byte[] pkcs12, String password) { }

    private static class CertificateKeySelector extends KeySelector {
        @Override
        public KeySelectorResult select(javax.xml.crypto.dsig.keyinfo.KeyInfo keyInfo,
                                        Purpose purpose,
                                        javax.xml.crypto.AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            for (Object item : keyInfo.getContent()) {
                if (item instanceof javax.xml.crypto.dsig.keyinfo.X509Data data) {
                    for (Object value : data.getContent()) {
                        if (value instanceof X509Certificate certificate) {
                            return certificate::getPublicKey;
                        }
                    }
                }
            }
            throw new KeySelectorException("No certificate in KeyInfo");
        }
    }
}
