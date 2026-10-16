package dev.nexo.erp.web;

import org.springframework.stereotype.Component;

@Component("uiLabels")
public class UiLabels {
    public String status(Object value) {
        if (value == null) return "Sin registrar";
        return switch (value.toString()) {
            case "ACCEPTED" -> "Aceptado";
            case "OBSERVED" -> "Aceptado con observaciones";
            case "REJECTED" -> "Rechazado";
            case "PENDING_GENERATION" -> "Por generar";
            case "GENERATED_UNSIGNED" -> "XML preparado, sin firmar";
            case "SIGNED" -> "Firmado";
            case "SUBMITTING" -> "Enviando";
            case "PROCESSING" -> "En proceso";
            case "SUBMISSION_UNKNOWN" -> "Envio por conciliar";
            case "CDR_RECORDED_UNVERIFIED" -> "CDR sin verificar";
            case "CANCELLED_LOCAL" -> "Cancelado sin envio";
            case "CANCELLED" -> "Cancelado";
            case "VOID_REQUESTED" -> "Baja solicitada";
            case "VOIDED" -> "Anulado";
            case "DRAFT" -> "Borrador";
            case "SENT" -> "Cotizado";
            case "CONFIRMED" -> "Confirmado";
            case "WAITING_STOCK" -> "Por abastecer";
            case "PARTIALLY_DISPATCHED" -> "Despacho parcial";
            case "DISPATCHED" -> "Despachado";
            case "PREPARED" -> "Preparado";
            case "PENDING" -> "Pendiente";
            case "PAID" -> "Pagado";
            case "PARTIAL" -> "Pago parcial";
            default -> value.toString();
        };
    }
}
