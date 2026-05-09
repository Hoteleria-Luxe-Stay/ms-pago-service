package com.hotel.pago.infrastructure.events;

import com.hotel.pago.core.outbox.service.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publisher de eventos de dominio del pago.
 *
 * Round 6: en lugar de publicar directo a Kafka (dual-write problem), persiste
 * el evento en la tabla outbox dentro de la transaccion del caller. El job
 * {@code OutboxRelayJob} lo publica a Kafka asincronicamente con garantia
 * at-least-once.
 *
 * Importante: este metodo DEBE ser llamado dentro de una transaccion @Transactional
 * activa. {@link OutboxPublisher} es {@code Propagation.MANDATORY} — falla
 * explicitamente si no hay tx, evitando inconsistencias por accidente.
 */
@Component
public class PagoEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PagoEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Pago";

    private final OutboxPublisher outboxPublisher;
    private final String topic;

    public PagoEventPublisher(OutboxPublisher outboxPublisher,
                              @Value("${app.kafka.topics.pago-events}") String topic) {
        this.outboxPublisher = outboxPublisher;
        this.topic = topic;
    }

    public void publish(PagoEvent event) {
        String aggregateId = event.getReservaId() != null
                ? event.getReservaId().toString()
                : (event.getPagoId() != null ? event.getPagoId().toString() : null);

        outboxPublisher.publish(
                topic,
                AGGREGATE_TYPE,
                aggregateId,
                event.getEventType(),
                event
        );
        LOGGER.debug("[OUTBOX] Encolado {} pagoId={} reservaId={}",
                event.getEventType(), event.getPagoId(), event.getReservaId());
    }
}
