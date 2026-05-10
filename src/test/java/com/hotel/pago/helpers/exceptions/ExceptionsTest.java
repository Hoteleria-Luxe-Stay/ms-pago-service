package com.hotel.pago.helpers.exceptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    // ==================== BusinessException ====================

    @Test
    void businessExceptionStoresMessageAndErrorCode() {
        BusinessException ex = new BusinessException("algo fallo", "ERR_001");

        assertThat(ex.getMessage()).isEqualTo("algo fallo");
        assertThat(ex.getErrorCode()).isEqualTo("ERR_001");
    }

    // ==================== EntityNotFoundException ====================

    @Test
    void entityNotFoundExceptionWithEntityAndId() {
        EntityNotFoundException ex = new EntityNotFoundException("Pago", 42L);

        assertThat(ex.getMessage()).contains("Pago").contains("42");
    }

    @Test
    void entityNotFoundExceptionWithMessage() {
        EntityNotFoundException ex = new EntityNotFoundException("Pago no encontrado");

        assertThat(ex.getMessage()).isEqualTo("Pago no encontrado");
    }

    // ==================== ValidationException ====================

    @Test
    void validationExceptionStoresFieldAndMessage() {
        ValidationException ex = new ValidationException("monto", "El monto debe ser positivo");

        assertThat(ex.getMessage()).isEqualTo("El monto debe ser positivo");
        assertThat(ex.getField()).isEqualTo("monto");
    }

    // ==================== PagoGatewayException ====================

    @Test
    void pagoGatewayExceptionStoresGatewayAndMessage() {
        PagoGatewayException ex = new PagoGatewayException("MERCADOPAGO", "Error al crear preference");

        assertThat(ex.getGateway()).isEqualTo("MERCADOPAGO");
        assertThat(ex.getMessage()).isEqualTo("Error al crear preference");
    }

    @Test
    void pagoGatewayExceptionWithCause() {
        RuntimeException cause = new RuntimeException("network error");
        PagoGatewayException ex = new PagoGatewayException("MERCADOPAGO", "fallo", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getGateway()).isEqualTo("MERCADOPAGO");
    }

    // ==================== WebhookValidationException ====================

    @Test
    void webhookValidationExceptionStoresMessage() {
        WebhookValidationException ex = new WebhookValidationException("HMAC invalido");

        assertThat(ex.getMessage()).isEqualTo("HMAC invalido");
    }

    @Test
    void webhookValidationExceptionWithCause() {
        RuntimeException cause = new RuntimeException("underlying");
        WebhookValidationException ex = new WebhookValidationException("bad sig", cause);

        assertThat(ex.getCause()).isSameAs(cause);
    }
}
