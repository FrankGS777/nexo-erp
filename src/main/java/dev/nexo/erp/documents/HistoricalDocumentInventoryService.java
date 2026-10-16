package dev.nexo.erp.documents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HistoricalDocumentInventoryService {

    private final String importRoot;

    public HistoricalDocumentInventoryService(
        @Value("${app.import-root:}") String importRoot
    ) {
        this.importRoot = importRoot;
    }

    public HistoricalDocumentInventory scan() {
        if (importRoot == null || importRoot.isBlank()) {
            return HistoricalDocumentInventory.unconfigured();
        }

        Path root = Path.of(importRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            return HistoricalDocumentInventory.unconfigured();
        }

        long invoices = 0;
        long creditNotes = 0;
        long emptyFiles = 0;

        try (Stream<Path> paths = Files.walk(root, 4)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String filename = path.getFileName().toString().toUpperCase(Locale.ROOT);
                if (!filename.endsWith(".XML")) {
                    continue;
                }
                if (filename.startsWith("FACTURA")) {
                    invoices++;
                } else if (filename.startsWith("NOTA_CREDITO")) {
                    creditNotes++;
                } else {
                    continue;
                }

                if (Files.size(path) == 0) {
                    emptyFiles++;
                }
            }
        } catch (IOException exception) {
            return HistoricalDocumentInventory.unconfigured();
        }

        return new HistoricalDocumentInventory(invoices, creditNotes, emptyFiles, true);
    }
}

