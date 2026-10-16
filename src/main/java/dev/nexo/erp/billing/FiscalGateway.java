package dev.nexo.erp.billing;

import java.nio.file.Path;

/** Boundary implemented by SUNAT direct and replaceable by a contracted PSE/OSE adapter. */
public interface FiscalGateway {
    SubmissionResult submit(DocumentReference document, Path unsignedXml);
    SubmissionResult submitSummary(SummaryReference summary, Path unsignedXml);
    SubmissionResult poll(String ticket, String expectedReference);
    SubmissionResult reconcile(DocumentReference document);

    record DocumentReference(long id, String type, String series, long number) { }
    record SummaryReference(long actionId, String fileName, String expectedReference) { }
    record SubmissionResult(Status status, String ticket, String code, String message,
                            byte[] signedXml, byte[] cdrArchive) {
        public SubmissionResult(Status status, String ticket, String code, String message) {
            this(status, ticket, code, message, null, null);
        }

        public enum Status {
            ACCEPTED, OBSERVED, REJECTED, PROCESSING, SUBMISSION_UNKNOWN, NOT_CONFIGURED
        }
    }
}
