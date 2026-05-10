package com.hotel.pago.core.pago.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EstadoPagoTest {

    @Test
    void pendingIsNotTerminal() {
        assertThat(EstadoPago.PENDING.esTerminal()).isFalse();
    }

    @Test
    void approvedIsTerminal() {
        assertThat(EstadoPago.APPROVED.esTerminal()).isTrue();
    }

    @Test
    void rejectedIsTerminal() {
        assertThat(EstadoPago.REJECTED.esTerminal()).isTrue();
    }

    @Test
    void enumValuesContainThreeEntries() {
        assertThat(EstadoPago.values()).hasSize(3);
    }
}
