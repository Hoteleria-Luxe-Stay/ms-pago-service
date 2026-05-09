package com.hotel.pago.core.pago.ports;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Datos necesarios para crear una sesion de checkout en el proveedor.
 *
 * NO conoce ningun tipo del SDK del proveedor — es el contrato de entrada
 * al puerto {@link PaymentGateway#createCheckoutSession}.
 *
 * @param amount      monto en la unidad MAYOR de la moneda (ej 100.50 PEN, no centavos).
 *                    La conversion a centavos la hace la implementacion del gateway.
 * @param currency    codigo ISO-4217 (ej "PEN", "USD")
 * @param description descripcion mostrada al usuario en el checkout
 * @param metadata    metadata libre que el gateway debe propagar al webhook (ej pagoId, reservaId)
 * @param successUrl  URL a la que el proveedor redirige tras pago exitoso
 * @param cancelUrl   URL a la que el proveedor redirige si el usuario cancela
 */
public record CheckoutSessionRequest(
        BigDecimal amount,
        String currency,
        String description,
        Map<String, String> metadata,
        String successUrl,
        String cancelUrl) {
}
