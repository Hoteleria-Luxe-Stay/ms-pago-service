package com.hotel.pago.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class CrearPagoRequest {

    @NotNull
    private Long reservaId;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true, message = "El monto debe ser mayor a 0")
    private BigDecimal monto;

    @Pattern(regexp = "^[A-Z]{3}$", message = "Moneda debe ser ISO 4217 (3 letras mayusculas)")
    private String moneda;

    @Size(max = 255)
    private String descripcion;

    @Size(max = 1000)
    private String successUrl;

    @Size(max = 1000)
    private String cancelUrl;

    public Long getReservaId() {
        return reservaId;
    }

    public void setReservaId(Long reservaId) {
        this.reservaId = reservaId;
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

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getSuccessUrl() {
        return successUrl;
    }

    public void setSuccessUrl(String successUrl) {
        this.successUrl = successUrl;
    }

    public String getCancelUrl() {
        return cancelUrl;
    }

    public void setCancelUrl(String cancelUrl) {
        this.cancelUrl = cancelUrl;
    }
}
