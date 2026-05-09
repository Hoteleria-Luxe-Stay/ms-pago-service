package com.hotel.pago.helpers.exceptions;

/**
 * Error al comunicarse con el proveedor de pago externo (MercadoPago, etc.).
 * Se traduce a HTTP 502 (Bad Gateway) en el GlobalExceptionHandler. El campo
 * {@code gateway} identifica al proveedor para logging y respuesta de error.
 */
public class PagoGatewayException extends RuntimeException {

    private final String gateway;

    public PagoGatewayException(String gateway, String message) {
        super(message);
        this.gateway = gateway;
    }

    public PagoGatewayException(String gateway, String message, Throwable cause) {
        super(message, cause);
        this.gateway = gateway;
    }

    public String getGateway() {
        return gateway;
    }
}
