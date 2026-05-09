package com.hotel.pago.core.pago.ports;

import java.util.Map;

/**
 * Puerto hexagonal del proveedor de pago.
 *
 * El dominio (PagoService, WebhookController) depende de esta abstraccion,
 * NO del SDK del proveedor. Cambiar de MercadoPago a Culqi/Niubiz/Stripe es
 * agregar una nueva implementacion en infrastructure/payment/ — el core no
 * se entera.
 *
 * Implementaciones: infrastructure/payment/mercadopago/MercadoPagoGateway.
 */
public interface PaymentGateway {

    /**
     * Crea una sesion de checkout hosteada por el proveedor.
     * Devuelve el ID de la sesion + la URL a la que el frontend debe redirigir.
     *
     * @throws com.hotel.pago.helpers.exceptions.PagoGatewayException si el proveedor falla
     */
    CheckoutSessionResult createCheckoutSession(CheckoutSessionRequest request);

    /**
     * Verifica la firma del webhook y resuelve el resultado del pago.
     *
     * Algunos proveedores (Stripe) entregan el estado del pago dentro del
     * payload del webhook. Otros (MercadoPago) solo entregan el ID y la
     * implementacion debe consultar al proveedor para obtener el estado real.
     * Esa diferencia queda OCULTA aca: el caller siempre recibe un resultado
     * normalizado.
     *
     * @param payload body crudo del request (necesario para HMAC)
     * @param headers headers del webhook (case-insensitive segun la spec HTTP)
     * @return evento normalizado o {@link WebhookEventResult#ignored()} si no nos interesa
     * @throws com.hotel.pago.helpers.exceptions.WebhookValidationException si la firma no valida
     * @throws com.hotel.pago.helpers.exceptions.PagoGatewayException       si falla la consulta de estado
     */
    WebhookEventResult verifyAndResolveWebhook(String payload, Map<String, String> headers);

    /**
     * Nombre del gateway, persistido en pago.gateway (ej: "MERCADOPAGO").
     */
    String getGatewayName();
}
