package com.hotel.pago.helpers.exceptions;

/**
 * Falla en la verificacion de firma del webhook (HMAC). Se traduce a HTTP 400 —
 * el webhook NO viene del proveedor esperado, o el secret esta mal configurado.
 * Headers segun el proveedor: {@code x-signature} en MercadoPago.
 */
public class WebhookValidationException extends RuntimeException {

    public WebhookValidationException(String message) {
        super(message);
    }

    public WebhookValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
