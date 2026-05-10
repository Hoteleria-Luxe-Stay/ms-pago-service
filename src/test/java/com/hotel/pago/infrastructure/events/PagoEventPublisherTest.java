package com.hotel.pago.infrastructure.events;

import com.hotel.pago.core.outbox.service.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PagoEventPublisherTest {

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private PagoEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(eventPublisher, "topic", "pago.events");
    }

    @Test
    void publishDelegatesToOutboxPublisherWithCorrectTopic() {
        PagoEvent event = buildEvent("PagoCreado", 1L, 10L);

        eventPublisher.publish(event);

        verify(outboxPublisher).publish(
                eq("pago.events"),
                eq("Pago"),
                eq("10"), // aggregateId = reservaId when not null
                eq("PagoCreado"),
                eq(event)
        );
    }

    @Test
    void publishUsesReservaIdAsAggregateIdWhenPresent() {
        PagoEvent event = buildEvent("PagoAprobado", 5L, 20L);

        eventPublisher.publish(event);

        ArgumentCaptor<String> aggregateIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher).publish(any(), any(), aggregateIdCaptor.capture(), any(), any());
        assertThat(aggregateIdCaptor.getValue()).isEqualTo("20");
    }

    @Test
    void publishUsesPagoIdAsAggregateIdWhenReservaIdIsNull() {
        PagoEvent event = buildEvent("PagoCreado", 7L, null);

        eventPublisher.publish(event);

        ArgumentCaptor<String> aggregateIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher).publish(any(), any(), aggregateIdCaptor.capture(), any(), any());
        assertThat(aggregateIdCaptor.getValue()).isEqualTo("7");
    }

    @Test
    void publishWithNullBothIdsUsesNullAggregateId() {
        PagoEvent event = buildEvent("PagoCreado", null, null);

        eventPublisher.publish(event);

        ArgumentCaptor<String> aggregateIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher).publish(any(), any(), aggregateIdCaptor.capture(), any(), any());
        assertThat(aggregateIdCaptor.getValue()).isNull();
    }

    // ==================== helpers ====================

    private PagoEvent buildEvent(String eventType, Long pagoId, Long reservaId) {
        PagoEvent event = new PagoEvent();
        event.setEventType(eventType);
        event.setPagoId(pagoId);
        event.setReservaId(reservaId);
        event.setEstado("PENDING");
        event.setMonto(new BigDecimal("100.00"));
        event.setMoneda("PEN");
        event.setTimestamp(LocalDateTime.now());
        return event;
    }
}
