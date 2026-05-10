package com.hotel.pago.core.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.pago.core.outbox.model.OutboxEvent;
import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OutboxPublisher publisher;

    @Test
    void publishPersistsOutboxEventWithCorrectFields() {
        Map<String, String> payload = Map.of("key", "value");

        publisher.publish("pago.events", "Pago", "42", "PagoCreado", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getTopic()).isEqualTo("pago.events");
        assertThat(saved.getAggregateType()).isEqualTo("Pago");
        assertThat(saved.getAggregateId()).isEqualTo("42");
        assertThat(saved.getEventType()).isEqualTo("PagoCreado");
        assertThat(saved.isSent()).isFalse();
        assertThat(saved.getAttempts()).isEqualTo(0);
        assertThat(saved.getPayload()).contains("key").contains("value");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void publishSerializesPayloadAsJson() {
        Map<String, Object> payload = Map.of("pagoId", 1L, "estado", "PENDING");

        publisher.publish("pago.events", "Pago", "1", "PagoCreado", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload()).startsWith("{");
    }

    @Test
    void publishThrowsIllegalStateWhenSerializationFails() throws Exception {
        ObjectMapper failingMapper = new ObjectMapper();
        // Un objeto no serializable
        Object nonSerializable = new Object() {
            @SuppressWarnings("unused")
            public Object selfRef = this; // cyclic reference causes exception
        };
        // Mockear el mapper para forzar error
        ObjectMapper mockMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(mockMapper.writeValueAsString(any())).thenThrow(
                new com.fasterxml.jackson.core.JsonProcessingException("serialization failed") {});

        OutboxPublisher failPublisher = new OutboxPublisher(outboxRepository, mockMapper);

        assertThatThrownBy(() -> failPublisher.publish("topic", "type", "id", "eventType", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudo serializar");
    }
}
