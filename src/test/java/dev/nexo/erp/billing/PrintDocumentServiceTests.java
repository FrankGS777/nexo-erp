package dev.nexo.erp.billing;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
class PrintDocumentServiceTests {
    @Test void rendersInvoiceAndNotesFromUblWithFullPrecisionAndCorrectTaxGroups(){
        for(String type:List.of("01","03","07","08")){
            var line=new UblDocumentGenerator.LineData(1,"SKU","Producto 4 L","NIU",new BigDecimal("72"),new BigDecimal("21.187"),new BigDecimal("1525.46"),new BigDecimal("274.58"),new BigDecimal("1800.04"));
            var data=new UblDocumentGenerator.DocumentData(type,"F001",1,LocalDate.now(),"PEN","00000000000","Nexo","Nexo","Callao","070106","6","00000000103","Cliente",new BigDecimal("1525.46"),new BigDecimal("274.58"),new BigDecimal("1800.04"),"01","F001-1","01","Prueba",List.of(line),"0101","CREDITO",LocalDate.now().plusDays(15),null,null,"Direccion cliente",null,null,null);
            var print=PrintDocumentService.parseXml(new UblDocumentGenerator().generate(data));
            assertThat(print.get("companyAddress")).isEqualTo("Callao");assertThat(print.get("customer")).isEqualTo("Cliente");
            assertThat((BigDecimal)print.get("total")).isEqualByComparingTo("1800.04");
            assertThat((BigDecimal)print.get("taxable")).isEqualByComparingTo("1525.46");
            assertThat((List<?>)print.get("lines")).hasSize(1);
            assertThat((BigDecimal)((Map<?,?>)((List<?>)print.get("lines")).getFirst()).get("grossPrice")).isPositive();
            if(type.equals("01")||type.equals("03"))assertThat((List<?>)print.get("installments")).hasSize(1);
        }
    }
    @Test void rejectsXmlExternalEntities(){
        assertThatThrownBy(()->PrintDocumentService.parseXml("<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><Invoice>&p;</Invoice>".getBytes()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesToPrintMissingTotalAsZero(){
        assertThatThrownBy(()->PrintDocumentService.parseXml("<CreditNote/>".getBytes())).hasMessageContaining("total valido");
    }
}
