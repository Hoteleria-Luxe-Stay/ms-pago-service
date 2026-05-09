package com.hotel.pago.core.pago.ports;

/**
 * Resultado normalizado de procesar un webhook entrante.
 *
 * El gateway resuelve el webhook completo (verifica firma + consulta el estado
 * real del pago al proveedor si hace falta) y devuelve el ID INTERNO del Pago.
 *
 * Razon de devolver pagoId interno (Long) y no un id del proveedor:
 * - Stripe: el webhook trae el sessionId que coincide con lo que persistimos.
 * - MercadoPago: el webhook trae un paymentId DISTINTO del preferenceId
 *   que persistimos. La correlacion se hace via external_reference (campo
 *   libre que MP propaga). El gateway sabe ese truco; el dominio NO.
 *
 * @param type          categoria del evento (ver {@link WebhookEventType})
 * @param pagoId        ID interno del Pago en nuestra BD (resuelto por el gateway).
 *                      Null para eventos IGNORED.
 * @param errorMessage  mensaje del error si type=PAYMENT_REJECTED.
 */
public record WebhookEventResult(
        WebhookEventType type,
        Long pagoId,
        String errorMessage) {

    public static WebhookEventResult approved(Long pagoId) {
        return new WebhookEventResult(WebhookEventType.PAYMENT_APPROVED, pagoId, null);
    }

    public static WebhookEventResult rejected(Long pagoId, String errorMessage) {
        return new WebhookEventResult(WebhookEventType.PAYMENT_REJECTED, pagoId, errorMessage);
    }

    public static WebhookEventResult ignored() {
        return new WebhookEventResult(WebhookEventType.IGNORED, null, null);
    }
}
