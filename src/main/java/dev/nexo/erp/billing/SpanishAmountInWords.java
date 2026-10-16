package dev.nexo.erp.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class SpanishAmountInWords {

    private static final String[] UNITS = {"CERO", "UNO", "DOS", "TRES", "CUATRO", "CINCO",
        "SEIS", "SIETE", "OCHO", "NUEVE", "DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE",
        "QUINCE", "DIECISEIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE",
        "VEINTIUNO", "VEINTIDOS", "VEINTITRES", "VEINTICUATRO", "VEINTICINCO",
        "VEINTISEIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] TENS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA",
        "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] HUNDREDS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS",
        "CUATROCIENTOS", "QUINIENTOS", "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    private SpanishAmountInWords() { }

    static String convert(BigDecimal amount, String currency) {
        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);
        long integer = normalized.longValue();
        int cents = normalized.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        String currencyName = "USD".equals(currency) ? "DOLARES AMERICANOS" : "SOLES";
        return "SON " + number(integer) + " CON " + String.format("%02d", cents)
            + "/100 " + currencyName;
    }

    private static String number(long value) {
        if (value == 0) return UNITS[0];
        if (value < 0 || value > 999_999_999_999L) return String.valueOf(value);
        if (value >= 1_000_000) {
            long millions = value / 1_000_000;
            String prefix = millions == 1 ? "UN MILLON" : number(millions) + " MILLONES";
            long remainder = value % 1_000_000;
            return remainder == 0 ? prefix : prefix + " " + number(remainder);
        }
        if (value >= 1_000) {
            long thousands = value / 1_000;
            String prefix = thousands == 1 ? "MIL" : number(thousands) + " MIL";
            long remainder = value % 1_000;
            return remainder == 0 ? prefix : prefix + " " + number(remainder);
        }
        if (value == 100) return "CIEN";
        if (value >= 100) {
            long remainder = value % 100;
            return HUNDREDS[(int) (value / 100)] + (remainder == 0 ? "" : " " + number(remainder));
        }
        if (value < 30) return UNITS[(int) value];
        long remainder = value % 10;
        return TENS[(int) (value / 10)] + (remainder == 0 ? "" : " Y " + UNITS[(int) remainder]);
    }
}
