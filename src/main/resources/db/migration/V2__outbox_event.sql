-- ============================================================================
-- Round 6 — Outbox Pattern (resolver dual-write Kafka + DB)
--
-- Toda emision de evento de dominio (PagoCreado / PagoAprobado / PagoRechazado)
-- se persiste primero en esta tabla, dentro de la MISMA transaccion del cambio
-- de estado del pago. Un job @Scheduled la lee, publica a Kafka y marca sent=1.
--
-- Garantia: at-least-once delivery (los consumers son idempotentes — Round 5.3).
-- ============================================================================

CREATE TABLE outbox_event (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    topic           VARCHAR(128)    NOT NULL,
    payload         MEDIUMTEXT      NOT NULL,
    headers         TEXT            NULL,
    created_at      DATETIME(6)     NOT NULL,
    sent            BIT(1)          NOT NULL DEFAULT b'0',
    sent_at         DATETIME(6)     NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000)   NULL,
    PRIMARY KEY (id),
    -- Index para el relay job: filtra por sent=0 ordenando por orden de insercion
    KEY idx_outbox_unsent (sent, id),
    -- Index para el cleanup job: borra sent=1 con sent_at viejo
    KEY idx_outbox_sent_at (sent, sent_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
