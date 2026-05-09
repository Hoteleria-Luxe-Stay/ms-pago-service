package com.hotel.pago.core.pago.service;

import com.hotel.pago.api.dto.CrearPagoRequest;
import com.hotel.pago.api.dto.CrearPagoResponse;
import com.hotel.pago.api.dto.PagoResponse;
import com.hotel.pago.core.pago.model.EstadoPago;
import com.hotel.pago.core.pago.model.Pago;
import com.hotel.pago.core.pago.ports.CheckoutSessionRequest;
import com.hotel.pago.core.pago.ports.CheckoutSessionResult;
import com.hotel.pago.core.pago.ports.PaymentGateway;
import com.hotel.pago.core.pago.repository.PagoRepository;
import com.hotel.pago.helpers.exceptions.EntityNotFoundException;
import com.hotel.pago.helpers.exceptions.ValidationException;
import com.hotel.pago.infrastructure.events.PagoEvent;
import com.hotel.pago.infrastructure.events.PagoEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class PagoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PagoService.class);

    private final PagoRepository pagoRepository;
    private final PaymentGateway paymentGateway;
    private final PagoEventPublisher pagoEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final String defaultCurrency;
    private final String defaultSuccessUrl;
    private final String defaultCancelUrl;

    public PagoService(PagoRepository pagoRepository,
                       PaymentGateway paymentGateway,
                       PagoEventPublisher pagoEventPublisher,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.pago.default-currency:PEN}") String defaultCurrency,
                       @Value("${app.pago.success-url}") String defaultSuccessUrl,
                       @Value("${app.pago.cancel-url}") String defaultCancelUrl) {
        this.pagoRepository = pagoRepository;
        this.paymentGateway = paymentGateway;
        this.pagoEventPublisher = pagoEventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.defaultCurrency = defaultCurrency;
        this.defaultSuccessUrl = defaultSuccessUrl;
        this.defaultCancelUrl = defaultCancelUrl;
    }

    /**
     * Crea un Pago + sesion de checkout en el proveedor configurado.
     *
     * Round 7 refactor: la llamada al gateway externo va FUERA de la transaccion
     * de DB (puede tardar 1-3s y mantener la tx abierta agota el connection pool).
     *
     * Estructura:
     *   Tx1 (rapida): persistir Pago(PENDING) y devolver el ID.
     *   Llamada al gateway (sin tx): crear checkout session.
     *   Tx2 (rapida): actualizar Pago con sessionId/url + outbox PagoCreado.
     *   Si el gateway falla: Tx3 compensatoria — marcar Pago=REJECTED para no
     *   dejar PENDING huerfanos sin checkoutUrl en BD (asi el SAGA libera slots).
     */
    public CrearPagoResponse crearPago(CrearPagoRequest request) {
        validarRequest(request);

        String moneda = request.getMoneda() != null ? request.getMoneda() : defaultCurrency;
        String successUrl = request.getSuccessUrl() != null ? request.getSuccessUrl() : defaultSuccessUrl;
        String cancelUrl = request.getCancelUrl() != null ? request.getCancelUrl() : defaultCancelUrl;
        String descripcion = request.getDescripcion() != null
                ? request.getDescripcion()
                : "Reserva #" + request.getReservaId();

        // Tx1: persistir Pago(PENDING) — fast commit
        Long pagoId = transactionTemplate.execute(status -> {
            Pago p = new Pago();
            p.setReservaId(request.getReservaId());
            p.setMonto(request.getMonto());
            p.setMoneda(moneda);
            p.setGateway(paymentGateway.getGatewayName());
            p.setEstado(EstadoPago.PENDING);
            return pagoRepository.save(p).getId();
        });

        // Llamada externa SIN transaccion abierta
        CheckoutSessionResult session;
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("pagoId", pagoId.toString());
            metadata.put("reservaId", request.getReservaId().toString());

            session = paymentGateway.createCheckoutSession(new CheckoutSessionRequest(
                    request.getMonto(),
                    moneda,
                    descripcion,
                    metadata,
                    successUrl,
                    cancelUrl
            ));
        } catch (RuntimeException ex) {
            // Compensacion: marcar el Pago como REJECTED y publicar evento.
            // ms-reserva libera el slot via PagoRechazado.
            LOGGER.error("Fallo creando session de pago para pagoId={}: {}. Marcando REJECTED.",
                    pagoId, ex.getMessage());
            transactionTemplate.executeWithoutResult(status -> {
                Pago p = pagoRepository.findById(pagoId).orElse(null);
                if (p != null && !p.getEstado().esTerminal()) {
                    p.setEstado(EstadoPago.REJECTED);
                    p.setErrorMessage("Gateway session creation failed: " + ex.getMessage());
                    pagoRepository.save(p);
                    pagoEventPublisher.publish(buildEvent("PagoRechazado", p));
                }
            });
            throw ex;
        }

        // Tx2: persistir gatewayPaymentId/checkoutUrl + outbox PagoCreado
        Pago pagoFinal = transactionTemplate.execute(status -> {
            Pago p = pagoRepository.findById(pagoId)
                    .orElseThrow(() -> new EntityNotFoundException("Pago", pagoId));
            p.setGatewayPaymentId(session.sessionId());
            p.setCheckoutUrl(session.checkoutUrl());
            p = pagoRepository.save(p);
            pagoEventPublisher.publish(buildEvent("PagoCreado", p));
            return p;
        });

        CrearPagoResponse response = new CrearPagoResponse();
        response.setPagoId(pagoFinal.getId());
        response.setCheckoutUrl(pagoFinal.getCheckoutUrl());
        response.setGatewayPaymentId(pagoFinal.getGatewayPaymentId());
        return response;
    }

    public PagoResponse obtenerPorId(Long id) {
        Pago pago = pagoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Pago", id));
        return toResponse(pago);
    }

    /**
     * Webhook handler: pago aprobado. Idempotente — si ya esta en estado terminal,
     * no hace nada (el proveedor puede reenviar el webhook).
     *
     * @param pagoId ID interno del Pago resuelto por el gateway desde el webhook
     */
    @Transactional
    public void marcarAprobado(Long pagoId) {
        Pago pago = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new EntityNotFoundException("Pago", pagoId));

        if (pago.getEstado().esTerminal()) {
            LOGGER.info("Pago {} ya en estado terminal {}, ignorando webhook (idempotencia)",
                    pago.getId(), pago.getEstado());
            return;
        }

        pago.setEstado(EstadoPago.APPROVED);
        pagoRepository.save(pago);

        pagoEventPublisher.publish(buildEvent("PagoAprobado", pago));
    }

    /**
     * Webhook handler: pago rechazado, sesion expirada o pago fallido. Idempotente.
     *
     * @param pagoId       ID interno del Pago resuelto por el gateway
     * @param errorMessage descripcion del rechazo (status/detail del proveedor)
     */
    @Transactional
    public void marcarRechazado(Long pagoId, String errorMessage) {
        Pago pago = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new EntityNotFoundException("Pago", pagoId));

        if (pago.getEstado().esTerminal()) {
            LOGGER.info("Pago {} ya en estado terminal {}, ignorando webhook (idempotencia)",
                    pago.getId(), pago.getEstado());
            return;
        }

        pago.setEstado(EstadoPago.REJECTED);
        pago.setErrorMessage(errorMessage);
        pagoRepository.save(pago);

        pagoEventPublisher.publish(buildEvent("PagoRechazado", pago));
    }

    private void validarRequest(CrearPagoRequest dto) {
        if (dto.getReservaId() == null) {
            throw new ValidationException("reservaId", "El reservaId es requerido");
        }
        if (dto.getMonto() == null || dto.getMonto().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("monto", "El monto debe ser positivo");
        }
    }

    private PagoEvent buildEvent(String eventType, Pago pago) {
        PagoEvent event = new PagoEvent();
        event.setEventType(eventType);
        event.setPagoId(pago.getId());
        event.setReservaId(pago.getReservaId());
        event.setEstado(pago.getEstado() != null ? pago.getEstado().name() : null);
        event.setMonto(pago.getMonto());
        event.setMoneda(pago.getMoneda());
        event.setGatewayPaymentId(pago.getGatewayPaymentId());
        event.setErrorMessage(pago.getErrorMessage());
        event.setTimestamp(LocalDateTime.now());
        return event;
    }

    private PagoResponse toResponse(Pago pago) {
        PagoResponse r = new PagoResponse();
        r.setId(pago.getId());
        r.setReservaId(pago.getReservaId());
        r.setMonto(pago.getMonto());
        r.setMoneda(pago.getMoneda());
        r.setGateway(pago.getGateway());
        r.setEstado(pago.getEstado() != null ? pago.getEstado().name() : null);
        r.setGatewayPaymentId(pago.getGatewayPaymentId());
        r.setCheckoutUrl(pago.getCheckoutUrl());
        r.setErrorMessage(pago.getErrorMessage());
        r.setCreatedAt(pago.getCreatedAt());
        r.setUpdatedAt(pago.getUpdatedAt());
        return r;
    }
}
