package dev.nexo.erp.billing;

import java.io.*;
import java.math.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import dev.nexo.erp.commercial.*;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;

@Service
public class PrintDocumentService {
    private final ElectronicDocumentService documents;
    private final CommercialService commercial;
    private final JdbcClient jdbc;
    private final boolean local;
    public PrintDocumentService(ElectronicDocumentService documents,CommercialService commercial,JdbcClient jdbc,Environment env) {
        this.documents=documents;this.commercial=commercial;this.jdbc=jdbc;
        local=Arrays.asList(env.getActiveProfiles()).contains("local");
    }
    public Map<String,Object> fiscal(long id) {
        var issued=documents.issuedDocument(id);
        Map<String,Object> m=parseXml(documents.printXml(id));
        boolean production="PRODUCTION".equals(jdbc.sql("select environment from fiscal_configuration order by id limit 1").query(String.class).single());
        boolean accepted=List.of("ACCEPTED","OBSERVED").contains(issued.status());
        m.put("notice",local||!production?"PRUEBA - SIN VALIDEZ TRIBUTARIA":accepted?"":"VISTA PREVIA - SIN ACEPTACION FISCAL");
        m.put("state",issued.status());
        m.put("footer",accepted&&production&&!local?"Representacion impresa del comprobante electronico. Conserve el XML y la constancia de recepcion. Verifique el comprobante en SUNAT.":"Esta representacion no acredita aceptacion fiscal. No utilizar como comprobante valido.");
        if(!String.valueOf(m.get("digest")).isBlank()) {
            String payload=String.join("|",String.valueOf(m.get("ruc")),issued.type(),issued.series(),String.valueOf(issued.number()),
                decimal(m.get("tax")).setScale(2).toPlainString(),decimal(m.get("total")).setScale(2).toPlainString(),String.valueOf(m.get("date")),
                String.valueOf(m.get("customerType")),String.valueOf(m.get("customerDocument")))+"|";
            m.put("qr",qr(payload));
        }
        return m;
    }
    public Map<String,Object> quote(long id,String username) {
        var o=commercial.order(id,username);
        if("DRAFT".equals(o.get("status")))throw new IllegalArgumentException("Finaliza la cotizacion antes de imprimirla.");
        var m=base();m.put("title","COTIZACION");m.put("number",o.get("reference"));m.put("customer",o.get("legal_name"));
        m.put("customerDocument",o.get("document_number"));m.put("customerType","6");m.put("address",Objects.toString(o.get("address"),""));
        m.put("delivery",Objects.toString(o.get("delivery_address"),""));m.put("date",o.get("created_at").toString().substring(0,10));
        m.put("due",o.get("valid_until"));m.put("dueLabel","Valida hasta");m.put("payment",o.get("payment_condition"));
        m.put("notes",Objects.toString(o.get("notes"),""));m.put("subtotal",o.get("subtotal"));m.put("tax",o.get("tax_amount"));m.put("total",o.get("total"));
        m.put("taxable",o.get("subtotal"));m.put("words",SpanishAmountInWords.convert(decimal(o.get("total")),"PEN"));m.put("state",o.get("status"));
        m.put("notice",local?"PRUEBA LOCAL - NO ES COMPROBANTE DE PAGO":"NO ES COMPROBANTE DE PAGO");
        m.put("footer","Cotizacion comercial. Entrega y fecha de despacho por coordinacion. No sustituye una factura ni una guia de remision.");
        List<Map<String,Object>> lines=new ArrayList<>();int index=1;
        for(var l:commercial.lines(id,username))lines.add(line(index++,l.get("sku"),l.get("description"),"NIU",l.get("quantity"),l.get("net_price"),l.get("line_net")));
        m.put("lines",lines);return m;
    }
    public Map<String,Object> dispatch(long order,long id,String username) {
        var o=commercial.order(order,username);
        var d=jdbc.sql("select * from commercial_dispatch where id=:id and order_id=:o").param("id",id).param("o",order).query().singleRow();
        var m=base();m.put("title","HOJA DE DESPACHO");m.put("number","DES-"+id);m.put("customer",o.get("legal_name"));m.put("customerDocument",o.get("document_number"));
        m.put("date",Objects.toString(d.get("transport_date"),"Por confirmar"));m.put("address",Objects.toString(o.get("address"),""));
        m.put("delivery",Objects.toString(d.get("arrival_address"),""));m.put("departure",Objects.toString(d.get("departure_address"),String.valueOf(m.get("companyAddress"))));
        m.put("guide",Objects.toString(d.get("guide_number"),"Sin registrar"));m.put("carrier",Objects.toString(d.get("carrier_ruc"),"Sin registrar"));m.put("plate",Objects.toString(d.get("vehicle_plate"),"Sin registrar"));
        m.put("notice","DOCUMENTO INTERNO - NO SUSTITUYE LA GRE");m.put("state",d.get("status"));m.put("transport",true);
        var transportFacts=new LinkedHashMap<String,String>();
        for(var entry:List.of(new String[]{"transport_mode","Modalidad"},new String[]{"carrier_name","Transportista"},new String[]{"driver_name","Conductor"},new String[]{"driver_document","Documento del conductor"},new String[]{"driver_license","Licencia"},new String[]{"vehicle_brand","Marca del vehiculo"},new String[]{"gross_weight","Peso bruto (kg)"},new String[]{"packages","Bultos"})) {
            Object value=d.get(entry[0]);transportFacts.put(entry[1],value instanceof BigDecimal b?b.stripTrailingZeros().toPlainString():Objects.toString(value,"No registrado"));
        }
        m.put("transportFacts",transportFacts);
        m.put("footer","La referencia de GRE es declarada por el operador y no ha sido verificada automaticamente. Porte la GRE oficial emitida en SUNAT para sustentar el traslado.");
        var rows=jdbc.sql("select p.sku,p.unit_code,l.description,dl.quantity from commercial_dispatch_line dl join commercial_order_line l on l.id=dl.order_line_id join product p on p.id=l.product_id where dl.dispatch_id=:id order by l.id").param("id",id).query().listOfRows();
        List<Map<String,Object>> lines=new ArrayList<>();int index=1;for(var l:rows)lines.add(line(index++,l.get("sku"),l.get("description"),l.get("unit_code"),l.get("quantity"),BigDecimal.ZERO,BigDecimal.ZERO));m.put("lines",lines);return m;
    }
    private Map<String,Object> base() {
        var m=defaults();var c=jdbc.sql("select * from company_profile where active=true order by id limit 1").query().singleRow();
        m.put("ruc",c.get("ruc"));m.put("company",c.get("legal_name"));m.put("companyAddress",c.get("fiscal_address"));return m;
    }
    static Map<String,Object> parseXml(byte[] bytes) {
        try {
            if(bytes.length>4*1024*1024)throw new IllegalArgumentException("XML demasiado grande");
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var root=f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();var m=defaults();
            String type=switch(root.getLocalName()){case "Invoice"->value(root,"InvoiceTypeCode");case "CreditNote"->"07";case "DebitNote"->"08";default->throw new IllegalArgumentException("Documento no imprimible");};
            m.put("title",switch(type){case "01"->"FACTURA ELECTRONICA";case "03"->"BOLETA DE VENTA ELECTRONICA";case "07"->"NOTA DE CREDITO ELECTRONICA";case "08"->"NOTA DE DEBITO ELECTRONICA";default->"DOCUMENTO ELECTRONICO";});
            m.put("number",value(root,"ID"));m.put("date",value(root,"IssueDate"));String currency=value(root,"DocumentCurrencyCode");m.put("currency",currency);m.put("symbol","USD".equals(currency)?"US$":"S/");
            m.put("ruc",value(root,"AccountingSupplierParty","Party","PartyIdentification","ID"));m.put("company",value(root,"AccountingSupplierParty","Party","PartyLegalEntity","RegistrationName"));
            m.put("companyAddress",value(root,"AccountingSupplierParty","Party","PartyLegalEntity","RegistrationAddress","AddressLine","Line"));
            m.put("customer",value(root,"AccountingCustomerParty","Party","PartyLegalEntity","RegistrationName"));m.put("customerDocument",value(root,"AccountingCustomerParty","Party","PartyIdentification","ID"));
            m.put("customerType",attribute(root,"schemeID","AccountingCustomerParty","Party","PartyIdentification","ID"));
            m.put("address",value(root,"AccountingCustomerParty","Party","PartyLegalEntity","RegistrationAddress","AddressLine","Line"));
            String monetary="08".equals(type)?"RequestedMonetaryTotal":"LegalMonetaryTotal";
            if(value(root,monetary,"PayableAmount").isBlank())throw new IllegalArgumentException("El XML no contiene un total valido para este tipo de comprobante.");
            m.put("subtotal",decimal(value(root,monetary,"LineExtensionAmount")));m.put("total",decimal(value(root,monetary,"PayableAmount")));m.put("tax",decimal(value(root,"TaxTotal","TaxAmount")));
            m.put("words",SpanishAmountInWords.convert(decimal(m.get("total")),currency));m.put("reference",value(root,"BillingReference","InvoiceDocumentReference","ID"));
            m.put("purchaseOrder",value(root,"OrderReference","ID"));m.put("guide",value(root,"DespatchDocumentReference","ID"));m.put("notes",value(root,"DiscrepancyResponse","Description"));
            m.put("digest",pathValue(root,".//*[local-name()='Signature']/*[local-name()='SignedInfo']/*[local-name()='Reference']/*[local-name()='DigestValue']"));
            List<Map<String,Object>> installments=new ArrayList<>();
            for(Element term:children(root,"PaymentTerms")){
                String p=value(term,"PaymentMeansID");if(p.equals("Contado")||p.equals("Credito"))m.put("payment",p);
                if(p.startsWith("Cuota"))installments.add(Map.of("number",p,"date",value(term,"PaymentDueDate"),"amount",decimal(value(term,"Amount"))));
            }
            m.put("installments",installments);
            m.put("due",value(root,"DueDate"));
            if(String.valueOf(m.get("due")).isBlank()&&!installments.isEmpty())m.put("due",installments.getLast().get("date"));
            for(Element tax:children(root,"TaxTotal"))for(Element sub:children(tax,"TaxSubtotal")) {
                String code=value(sub,"TaxCategory","TaxScheme","ID"),key=switch(code){case "1000"->"taxable";case "9997"->"exempt";case "9998"->"unaffected";default->null;};
                if(key!=null)m.put(key,decimal(m.get(key)).add(decimal(value(sub,"TaxableAmount"))));
            }
            List<Map<String,Object>> lines=new ArrayList<>();String tag="07".equals(type)?"CreditNoteLine":"08".equals(type)?"DebitNoteLine":"InvoiceLine";
            String quantity="07".equals(type)?"CreditedQuantity":"08".equals(type)?"DebitedQuantity":"InvoicedQuantity";
            for(Element l:children(root,tag)){
                var printed=new LinkedHashMap<>(line(lines.size()+1,value(l,"Item","SellersItemIdentification","ID"),value(l,"Item","Description"),attribute(l,"unitCode",quantity),decimal(value(l,quantity)),decimal(value(l,"Price","PriceAmount")),decimal(value(l,"LineExtensionAmount"))));
                String gross=value(l,"PricingReference","AlternativeConditionPrice","PriceAmount");
                printed.put("grossPrice",gross.isBlank()?null:decimal(gross));
                lines.add(printed);
            }
            m.put("lines",lines);return m;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("No se pudo leer el XML para la representacion impresa.");}
    }
    private static Map<String,Object> defaults(){var m=new LinkedHashMap<String,Object>();for(String key:List.of("title","number","ruc","company","companyAddress","customer","customerDocument","customerType","address","delivery","date","due","payment","notes","reference","purchaseOrder","guide","digest","qr","words","notice","state","footer"))m.put(key,"");for(String key:List.of("subtotal","tax","total","taxable","exempt","unaffected"))m.put(key,BigDecimal.ZERO);m.put("currency","PEN");m.put("symbol","S/");m.put("dueLabel","Vencimiento");m.put("transport",false);m.put("installments",List.of());return m;}
    private static Map<String,Object> line(int n,Object sku,Object text,Object unit,Object qty,Object price,Object total){return Map.of("number",n,"sku",Objects.toString(sku,""),"description",text,"unit",unit,"quantity",qty,"price",price,"total",total);}
    private static BigDecimal decimal(Object value){return value==null||value.toString().isBlank()?BigDecimal.ZERO:new BigDecimal(value.toString());}
    private static List<Element> children(Element root,String name){List<Element> result=new ArrayList<>();for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&name.equals(e.getLocalName()))result.add(e);return result;}
    private static Element child(Element root,String... path){for(String name:path){if(root==null)return null;root=children(root,name).stream().findFirst().orElse(null);}return root;}
    private static String value(Element root,String... path){Element e=child(root,path);return e==null?"":e.getTextContent().strip();}
    private static String attribute(Element root,String attribute,String... path){Element e=child(root,path);return e==null?"":e.getAttribute(attribute);}
    private static String pathValue(Element root,String path)throws XPathExpressionException{return XPathFactory.newInstance().newXPath().evaluate(path,root);}
    private static String qr(String payload){try{var bits=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,240,240,Map.of(EncodeHintType.MARGIN,2));var img=new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);for(int y=0;y<240;y++)for(int x=0;x<240;x++)img.setRGB(x,y,bits.get(x,y)?0:0xffffff);var out=new ByteArrayOutputStream();ImageIO.write(img,"png",out);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());}catch(Exception e){throw new IllegalArgumentException("No se pudo generar el QR.");}}
}
