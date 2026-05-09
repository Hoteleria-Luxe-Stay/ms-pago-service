package com.hotel.pago.infrastructure.jobs;

import com.hotel.pago.core.outbox.service.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tick del relay del outbox. {@code fixedDelay} (no fixedRate) para evitar
 * solapamientos: si un batch tarda mas que el delay, el siguiente arranca DESPUES
 * de que termine, no en paralelo.
 *
 * Para escalar horizontalmente: el SELECT FOR UPDATE SKIP LOCKED permite que cada
 * instancia tome batches distintos sin coordinacion adicional.
 */
@Component
public class OutboxRelayJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxRelayService outboxRelayService;

    public OutboxRelayJob(OutboxRelayService outboxRelayService) {
        this.outboxRelayService = outboxRelayService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.fixed-delay-ms:2000}",
               initialDelayString = "${app.outbox.relay.initial-delay-ms:5000}")
    public void run() {
        try {
            outboxRelayService.relayBatch();
        } catch (RuntimeException ex) {
            // Una falla del batch entero (DB caida, lock timeout) no debe matar el scheduler.
            LOGGER.error("[OUTBOX] Error en tick del relay: {}", ex.getMessage());
        }
    }
}
