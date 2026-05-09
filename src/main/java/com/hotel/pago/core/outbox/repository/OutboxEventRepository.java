package com.hotel.pago.core.outbox.repository;

import com.hotel.pago.core.outbox.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Lee un lote de eventos no enviados con FOR UPDATE SKIP LOCKED.
     *
     * Por que SKIP LOCKED: si corren multiples instancias del relay job (por ejemplo
     * en escalado horizontal de ms-pago), cada una se queda con un lote distinto sin
     * bloquear a las demas. Si una instancia se cuelga procesando, las otras siguen.
     *
     * Requiere MySQL 8.0+. Es nativo, no JPQL — JPA no estandariza SKIP LOCKED.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE sent = 0 AND dead = 0
            ORDER BY id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnsentBatchForUpdate(@Param("batchSize") int batchSize);

    /**
     * Cleanup: borra los eventos enviados hace mas de N dias.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.sent = true AND o.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") LocalDateTime cutoff);
}
