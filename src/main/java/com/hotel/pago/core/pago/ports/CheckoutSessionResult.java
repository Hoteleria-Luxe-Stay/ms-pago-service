package com.hotel.pago.core.pago.ports;

/**
 * Resultado de crear una sesion de checkout. Lo que el dominio necesita
 * persistir + devolver al frontend.
 *
 * @param sessionId   identificador de la sesion en el proveedor (Stripe: cs_test_*,
 *                    MercadoPago: preferenceId). Se guarda en pago.gateway_payment_id
 *                    y sirve para correlacionar el webhook con el pago.
 * @param checkoutUrl URL hosteada del proveedor a la que el frontend redirige al usuario.
 */
public record CheckoutSessionResult(
        String sessionId,
        String checkoutUrl) {
}
