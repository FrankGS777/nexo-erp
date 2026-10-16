package dev.nexo.erp.documents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HistoricalDocumentCatalog {

    private final Path importRoot;
    private final HistoricalDocumentReader reader;
    private volatile List<HistoricalDocument> cachedDocuments;

    public HistoricalDocumentCatalog(
        @Value("${app.import-root:}") String importRoot,
        HistoricalDocumentReader reader
    ) {
        this.importRoot = importRoot == null || importRoot.isBlank() ? null : Path.of(importRoot).normalize();
        this.reader = reader;
    }

    public List<HistoricalDocument> find(String query, String type, String state) {
        String safeQuery = normalize(query);
        String safeType = normalize(type);
        String safeState = normalize(state);

        return documents().stream()
            .filter(document -> safeQuery.isBlank()
                || normalize(document.documentNumber()).contains(safeQuery)
                || normalize(document.customerDocument()).contains(safeQuery)
                || normalize(document.customerName()).contains(safeQuery))
            .filter(document -> safeType.isBlank() || normalize(document.documentType()).contains(safeType))
            .filter(document -> safeState.isBlank() || normalize(document.validationState().name()).equals(safeState))
            .toList();
    }

    public List<HistoricalDocument> documents() {
        List<HistoricalDocument> local = cachedDocuments;
        if (local == null) {
            synchronized (this) {
                local = cachedDocuments;
                if (local == null) {
                    local = load();
                    cachedDocuments = local;
                }
            }
        }
        return local;
    }

    private List<HistoricalDocument> load() {
        if (importRoot == null || !Files.isDirectory(importRoot)) {
            return List.of();
        }

        List<HistoricalDocument> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(importRoot, 4)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isBusinessXml)
                .forEach(path -> documents.add(reader.read(path)));
        } catch (Exception ignored) {
            return List.of();
        }

        return documents.stream()
            .sorted(Comparator.comparing(
                HistoricalDocument::issueDate,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(HistoricalDocument::documentNumber))
            .toList();
    }

    private boolean isBusinessXml(Path path) {
        String name = path.getFileName().toString().toUpperCase(Locale.ROOT);
        return name.endsWith(".XML") && (name.contains("FACTURA") || name.contains("NOTA_CREDITO"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
