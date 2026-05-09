package com.hotel.pago.infrastructure.jobs;

import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Limpieza periodica del outbox: borra los eventos {@code sent=true} cuyo
 * {@code sentAt} sea mas viejo que la retencion configurada (default 7 dias).
 *
 * Por que retener una semana: ventana razonable para forensics si surge un bug
 * de produccion donde necesites confirmar "el evento X se publico el dia tal".
 * Con miles de eventos por semana, una tabla con eventos sin retencion creceria
 * indefinidamente — eso si nos importaria para performance del relay y backups.
 *
 * Cron: cada 24h, hora configurable. Default 03:00 UTC (off-peak).
 */
@Component
public class OutboxCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository outboxRepository;

    @Value("${app.outbox.cleanup.retention-days:7}")
    private int retentionDays;

    public OutboxCleanupJob(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(cron = "${app.outbox.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = outboxRepository.deleteSentBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("[OUTBOX-CLEANUP] Eliminados {} eventos sent con sentAt < {}",
                    deleted, cutoff);
        }
    }
}
