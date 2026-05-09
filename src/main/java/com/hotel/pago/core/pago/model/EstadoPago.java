package com.hotel.pago.core.pago.model;

public enum EstadoPago {
    PENDING,
    APPROVED,
    REJECTED;

    public boolean esTerminal() {
        return this == APPROVED || this == REJECTED;
    }
}
