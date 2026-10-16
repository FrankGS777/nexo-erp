package dev.nexo.erp.documents;

public record HistoricalDocumentInventory(
    long invoices,
    long creditNotes,
    long emptyFiles,
    boolean configured
) {
    public long total() {
        return invoices + creditNotes;
    }

    public static HistoricalDocumentInventory unconfigured() {
        return new HistoricalDocumentInventory(0, 0, 0, false);
    }
}

