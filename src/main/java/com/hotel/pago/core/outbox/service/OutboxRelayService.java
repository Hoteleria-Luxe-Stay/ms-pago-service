package com.hotel.pago.core.outbox.service;

import com.hotel.pago.core.outbox.model.OutboxEvent;
import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relay del outbox: lee eventos no enviados, los publica a Kafka, marca sent=true.
 *
 * Cada batch corre en su propia transaccion (REQUIRES_NEW desde el job programado).
 * El SELECT usa FOR UPDATE SKIP LOCKED para que multiples instancias coordinen sin
 * pisarse.
 *
 * Publicacion sincrona: hacemos {@code .get()} en el Future para confirmar el ack
 * de Kafka antes de marcar sent=true. Si Kafka responde con error, la transaccion
 * hace rollback (los locks se liberan, el evento queda unsent), y un proximo tick
 * reintenta. Eso es justamente lo que queremos.
 *
 * Performance: si las llamadas .get() resultan ser un cuello de botella, el siguiente
 * paso seria un patron async en batches. Para el volumen de este sistema (decenas de
 * pagos por minuto en pico) la version sincrona es mas que suficiente.
 */
@Service
public class OutboxRelayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${app.outbox.max-attempts:10}")
    private int maxAttempts;

    public OutboxRelayService(OutboxEventRepository outboxRepository,
                              KafkaTemplate<String, String> outboxKafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.outboxKafkaTemplate = outboxKafkaTemplate;
    }

    /**
     * Procesa un batch. Se llama desde {@code OutboxRelayJob} con propagation
     * REQUIRES_NEW para aislar el lock del batch del scheduler thread.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int relayBatch() {
        List<OutboxEvent> batch = outboxRepository.findUnsentBatchForUpdate(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                publishToKafka(event);
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
                event.setLastError(null);
                sent++;
            } catch (Exception ex) {
                // Marcamos el intento pero NO seteamos sent=true. La transaccion
                // continua (los siguientes pueden tener exito). En el proximo tick,
                // el SELECT volvera a tomar este evento (sent=0).
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(truncate(ex.getMessage(), 1000));
                if (attempts >= maxAttempts) {
                    // Dead-letter: superado el limite de reintentos, dejamos de
                    // tomar el evento. Requiere intervencion manual (republicar,
                    // descartar, alerta a oncall).
                    event.setDead(true);
                    LOGGER.error("[OUTBOX][DEAD] Evento id={} topic={} marcado dead tras {} intentos. Ultimo error: {}",
                            event.getId(), event.getTopic(), attempts, ex.getMessage());
                } else {
                    LOGGER.error("[OUTBOX] Fallo publicando evento id={} topic={} attempt={}/{}: {}",
                            event.getId(), event.getTopic(), attempts, maxAttempts, ex.getMessage());
                }
            }
        }
        outboxRepository.saveAll(batch);
        if (sent > 0) {
            LOGGER.info("[OUTBOX] Publicados {} de {} evento(s) en este batch", sent, batch.size());
        }
        return sent;
    }

    private void publishToKafka(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getTopic(),
                null,
                event.getAggregateId(),
                event.getPayload()
        );
        // Headers para tracing y para que el consumer pueda filtrar por tipo
        // sin parsear el payload.
        record.headers().add(new RecordHeader(
                "event-type", bytes(event.getEventType())
        ));
        record.headers().add(new RecordHeader(
                "aggregate-type", bytes(event.getAggregateType())
        ));
        record.headers().add(new RecordHeader(
                "outbox-id", bytes(String.valueOf(event.getId()))
        ));
        outboxKafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
