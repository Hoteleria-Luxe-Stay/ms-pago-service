-- =========================================================
-- ms-pago: Baseline schema
-- =========================================================
-- Tabla 'pago' con FK LOGICA a reserva (no FK fisica porque ms-reserva
-- vive en otra base de datos). gateway_payment_id es el ID que devuelve
-- Stripe (Checkout Session ID en este caso).
-- =========================================================

CREATE TABLE pago (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    reserva_id           BIGINT        NOT NULL,
    monto                DECIMAL(12, 2) NOT NULL,
    moneda               VARCHAR(3)    NOT NULL,
    gateway              VARCHAR(20)   NOT NULL,
    gateway_payment_id   VARCHAR(255),
    estado               VARCHAR(32)   NOT NULL,
    checkout_url         VARCHAR(1000),
    error_message        VARCHAR(500),
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_pago PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_pago_reserva_id          ON pago (reserva_id);
CREATE INDEX idx_pago_gateway_payment_id  ON pago (gateway_payment_id);
CREATE INDEX idx_pago_estado              ON pago (estado);
CREATE INDEX idx_pago_created_at          ON pago (created_at);
