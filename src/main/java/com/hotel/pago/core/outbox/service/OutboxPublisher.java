package com.hotel.pago.core.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.pago.core.outbox.model.OutboxEvent;
import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Punto unico de emision de eventos de dominio.
 *
 * En lugar de publicar directo a Kafka (dual-write problem), serializa el payload
 * a JSON y persiste un registro en la tabla {@code outbox_event} dentro de la
 * MISMA transaccion del caller. Asi, si la transaccion del caller hace rollback,
 * el evento NO queda persistido.
 *
 * Importante: {@link Propagation#MANDATORY} obliga a que exista una transaccion
 * activa cuando se llama a {@link #publish}. Si alguien llama desde un metodo
 * sin @Transactional, el llamado falla con TransactionRequiredException —
 * justamente lo que queremos para que no se rompa la atomicidad por accidente.
 */
@Component
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic,
                        String aggregateType,
                        String aggregateId,
                        String eventType,
                        Object payload) {
        String json = serialize(payload);
        OutboxEvent event = new OutboxEvent();
        event.setTopic(topic);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(json);
        event.setCreatedAt(LocalDateTime.now());
        event.setSent(false);
        event.setAttempts(0);
        outboxRepository.save(event);
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se pudo serializar payload de outbox: " + payload.getClass().getName(), e
            );
        }
    }
}
