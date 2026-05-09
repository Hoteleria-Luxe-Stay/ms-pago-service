package com.hotel.pago.core.pago.ports;

/**
 * Tipos de evento de webhook que al dominio le interesan.
 *
 * Los proveedores tienen catalogos distintos (Stripe: "checkout.session.completed",
 * MercadoPago: "payment.updated" + GET /v1/payments/{id} para leer status). Aca
 * los normalizamos a 3 categorias que el SAGA entiende.
 */
public enum WebhookEventType {

    /** Pago confirmado por el proveedor. Trigger: marcarAprobado en PagoService. */
    PAYMENT_APPROVED,

    /** Pago rechazado, expirado o cancelado. Trigger: marcarRechazado en PagoService. */
    PAYMENT_REJECTED,

    /** Evento valido pero que no nos interesa procesar (ej: payment.created sin status final). */
    IGNORED
}
