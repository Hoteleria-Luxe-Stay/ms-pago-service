package com.hotel.pago.infrastructure.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evento publicado al topic Kafka 'pago.events'. Lo consume ms-reserva (Ronda 5.3)
 * para reaccionar al resultado del pago: si PagoAprobado → confirmar reserva;
 * si PagoRechazado → liberar slots y marcar PAGO_FALLIDO.
 */
public class PagoEvent {

    private String eventType;          // "PagoCreado" | "PagoAprobado" | "PagoRechazado"
    private Long pagoId;
    private Long reservaId;
    private String estado;
    private BigDecimal monto;
    private String moneda;
    private String gatewayPaymentId;
    private String errorMessage;
    private LocalDateTime timestamp;

    public PagoEvent() {
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getPagoId() {
        return pagoId;
    }

    public void setPagoId(Long pagoId) {
        this.pagoId = pagoId;
    }

    public Long getReservaId() {
        return reservaId;
    }

    public void setReservaId(Long reservaId) {
        this.reservaId = reservaId;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public void setMonto(BigDecimal monto) {
        this.monto = monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public void setMoneda(String moneda) {
        this.moneda = moneda;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public void setGatewayPaymentId(String gatewayPaymentId) {
        this.gatewayPaymentId = gatewayPaymentId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
