package dev.nexo.erp.catalog;

import java.math.BigDecimal;

public final class Quantities {
    private Quantities() { }

    public static void validate(BigDecimal quantity, String unitCode) {
        if (quantity == null || quantity.abs().compareTo(new BigDecimal("9999999")) > 0)
            throw new IllegalArgumentException("Cantidad fuera de rango");
        if ("NIU".equals(unitCode) && quantity.stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("La cantidad de envases debe ser un numero entero");
        if (quantity.stripTrailingZeros().scale() > 4)
            throw new IllegalArgumentException("La cantidad admite hasta cuatro decimales para unidades de medida a granel");
    }
}
