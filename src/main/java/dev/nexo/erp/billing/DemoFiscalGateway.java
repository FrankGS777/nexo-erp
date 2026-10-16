package dev.nexo.erp.billing;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** The public edition never signs, sends, accepts, or cancels fiscal documents. */
@Component
public final class DemoFiscalGateway implements FiscalGateway {
    private SubmissionResult blocked() {
        return new SubmissionResult(SubmissionResult.Status.NOT_CONFIGURED, null,
            "DEMO_NO_FISCAL_CONNECTION", "Demostracion sin validez fiscal. No se contacta a SUNAT.");
    }
    @Override public SubmissionResult submit(DocumentReference document, Path xml) { return blocked(); }
    @Override public SubmissionResult submitSummary(SummaryReference summary, Path xml) { return blocked(); }
    @Override public SubmissionResult poll(String ticket, String reference) { return blocked(); }
    @Override public SubmissionResult reconcile(DocumentReference document) { return blocked(); }
}
