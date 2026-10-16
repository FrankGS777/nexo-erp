package dev.nexo.erp.catalog;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class IdentityLookupService {
    public Result lookup(String type, String number, String username) {
        throw new IllegalArgumentException("Consulta externa no disponible en la demostracion. Usa los clientes ficticios del catalogo.");
    }
    public record Result(String documentType, String documentNumber, String legalName,
                         String tradeName, String address, String ubigeo,
                         Map<String, String> details, String provider, Instant consultedAt) { }
}
