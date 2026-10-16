package dev.nexo.erp.documents;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HistoricalDocument(
    String documentType,
    String documentNumber,
    LocalDate issueDate,
    String customerDocument,
    String customerName,
    String currency,
    BigDecimal total,
    String fileName,
    String sha256,
    boolean digitallySigned,
    ValidationState validationState,
    String observation
) {

    public enum ValidationState {
        READABLE,
        EMPTY,
        INVALID
    }
}
