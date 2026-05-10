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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    @Mock private PagoRepository pagoRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PagoEventPublisher pagoEventPublisher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private PagoService pagoService;

    @BeforeEach
    void setUp() {
        pagoService = new PagoService(
                pagoRepository,
                paymentGateway,
                pagoEventPublisher,
                transactionManager,
                "PEN",
                "https://success.example.com",
                "https://cancel.example.com"
        );
    }

    /**
     * Configura el mock del TransactionManager para que ejecute el callback directamente.
     * Solo se llama en tests que invocan crearPago (que usa TransactionTemplate).
     */
    private void configurarTransactionManagerMock() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    // ==================== validarRequest (no usa TransactionManager) ====================

    @Test
    void crearPagoThrowsValidationExceptionWhenReservaIdIsNull() {
        CrearPagoRequest req = buildRequest(null, BigDecimal.TEN);

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reservaId");
    }

    @Test
    void crearPagoThrowsValidationExceptionWhenMontoIsNull() {
        CrearPagoRequest req = buildRequest(1L, null);

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("monto");
    }

    @Test
    void crearPagoThrowsValidationExceptionWhenMontoIsZero() {
        CrearPagoRequest req = buildRequest(1L, BigDecimal.ZERO);

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("monto");
    }

    @Test
    void crearPagoThrowsValidationExceptionWhenMontoIsNegative() {
        CrearPagoRequest req = buildRequest(1L, new BigDecimal("-1.00"));

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(ValidationException.class);
    }

    // ==================== crearPago — happy path ====================

    @Test
    void crearPagoHappyPath() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("150.00"));

        Pago savedPago = buildPago(1L, EstadoPago.PENDING);
        CheckoutSessionResult sessionResult = new CheckoutSessionResult("pref-123", "https://mp.com/checkout");
        Pago updatedPago = buildPago(1L, EstadoPago.PENDING);
        updatedPago.setGatewayPaymentId("pref-123");
        updatedPago.setCheckoutUrl("https://mp.com/checkout");

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        // Tx1: save PENDING pago
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        // Gateway call
        when(paymentGateway.createCheckoutSession(any(CheckoutSessionRequest.class))).thenReturn(sessionResult);
        // Tx2: findById + save updated pago
        when(pagoRepository.findById(1L)).thenReturn(Optional.of(updatedPago));
        when(pagoRepository.save(updatedPago)).thenReturn(updatedPago);

        CrearPagoResponse response = pagoService.crearPago(req);

        assertThat(response.getCheckoutUrl()).isEqualTo("https://mp.com/checkout");
        assertThat(response.getGatewayPaymentId()).isEqualTo("pref-123");
        verify(pagoEventPublisher, atLeastOnce()).publish(any(PagoEvent.class));
    }

    @Test
    void crearPagoUsesDefaultCurrencyWhenNotSpecified() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("100.00"));
        req.setMoneda(null); // usara default PEN

        Pago savedPago = buildPago(1L, EstadoPago.PENDING);
        CheckoutSessionResult sessionResult = new CheckoutSessionResult("pref-abc", "https://mp.com/checkout");
        Pago updatedPago = buildPago(1L, EstadoPago.PENDING);
        updatedPago.setGatewayPaymentId("pref-abc");
        updatedPago.setCheckoutUrl("https://mp.com/checkout");

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any(CheckoutSessionRequest.class))).thenReturn(sessionResult);
        when(pagoRepository.findById(1L)).thenReturn(Optional.of(updatedPago));
        when(pagoRepository.save(updatedPago)).thenReturn(updatedPago);

        ArgumentCaptor<CheckoutSessionRequest> captor = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        pagoService.crearPago(req);

        verify(paymentGateway).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().currency()).isEqualTo("PEN");
    }

    @Test
    void crearPagoUsesDefaultDescripcionWhenNotSpecified() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("100.00"));
        req.setDescripcion(null);

        Pago savedPago = buildPago(1L, EstadoPago.PENDING);
        CheckoutSessionResult sessionResult = new CheckoutSessionResult("pref-xyz", "https://mp.com/checkout");
        Pago updatedPago = buildPago(1L, EstadoPago.PENDING);
        updatedPago.setGatewayPaymentId("pref-xyz");
        updatedPago.setCheckoutUrl("https://mp.com/checkout");

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any(CheckoutSessionRequest.class))).thenReturn(sessionResult);
        when(pagoRepository.findById(1L)).thenReturn(Optional.of(updatedPago));
        when(pagoRepository.save(updatedPago)).thenReturn(updatedPago);

        ArgumentCaptor<CheckoutSessionRequest> captor = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        pagoService.crearPago(req);

        verify(paymentGateway).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().description()).contains("Reserva #10");
    }

    @Test
    void crearPagoUsesCustomUrlsWhenProvided() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("100.00"));
        req.setSuccessUrl("https://custom.com/success");
        req.setCancelUrl("https://custom.com/cancel");

        Pago savedPago = buildPago(1L, EstadoPago.PENDING);
        CheckoutSessionResult sessionResult = new CheckoutSessionResult("pref-custom", "https://mp.com/checkout");
        Pago updatedPago = buildPago(1L, EstadoPago.PENDING);
        updatedPago.setGatewayPaymentId("pref-custom");
        updatedPago.setCheckoutUrl("https://mp.com/checkout");

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any(CheckoutSessionRequest.class))).thenReturn(sessionResult);
        when(pagoRepository.findById(1L)).thenReturn(Optional.of(updatedPago));
        when(pagoRepository.save(updatedPago)).thenReturn(updatedPago);

        ArgumentCaptor<CheckoutSessionRequest> captor = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        pagoService.crearPago(req);

        verify(paymentGateway).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().successUrl()).isEqualTo("https://custom.com/success");
        assertThat(captor.getValue().cancelUrl()).isEqualTo("https://custom.com/cancel");
    }

    // ==================== crearPago — gateway failure compensation ====================

    @Test
    void crearPagoMarcarRejectedAndRethrowsWhenGatewayFails() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("200.00"));

        Pago savedPago = buildPago(5L, EstadoPago.PENDING);

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any())).thenThrow(new RuntimeException("MP timeout"));
        // Compensacion: findById para marcar REJECTED
        when(pagoRepository.findById(5L)).thenReturn(Optional.of(savedPago));

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MP timeout");

        // La compensacion debe haber marcado el pago como REJECTED
        assertThat(savedPago.getEstado()).isEqualTo(EstadoPago.REJECTED);
        assertThat(savedPago.getErrorMessage()).contains("Gateway session creation failed");
        verify(pagoEventPublisher).publish(any(PagoEvent.class));
    }

    @Test
    void crearPagoCompensacionSkipsIfPagoAlreadyTerminal() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("200.00"));

        Pago savedPago = buildPago(5L, EstadoPago.PENDING);

        // Pago que ya llego a estado terminal (e.g. race condition)
        Pago terminalPago = buildPago(5L, EstadoPago.APPROVED);

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any())).thenThrow(new RuntimeException("timeout"));
        when(pagoRepository.findById(5L)).thenReturn(Optional.of(terminalPago));

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(RuntimeException.class);

        // No debe publicar evento porque el pago ya era terminal
        verify(pagoEventPublisher, never()).publish(any(PagoEvent.class));
    }

    @Test
    void crearPagoCompensacionSkipsIfPagoNotFound() {
        configurarTransactionManagerMock();
        CrearPagoRequest req = buildRequest(10L, new BigDecimal("200.00"));

        Pago savedPago = buildPago(5L, EstadoPago.PENDING);

        when(paymentGateway.getGatewayName()).thenReturn("MERCADOPAGO");
        when(pagoRepository.save(any(Pago.class))).thenReturn(savedPago);
        when(paymentGateway.createCheckoutSession(any())).thenThrow(new RuntimeException("timeout"));
        when(pagoRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pagoService.crearPago(req))
                .isInstanceOf(RuntimeException.class);

        verify(pagoEventPublisher, never()).publish(any(PagoEvent.class));
    }

    // ==================== obtenerPorId ====================

    @Test
    void obtenerPorIdReturnsPagoResponse() {
        Pago pago = buildPago(1L, EstadoPago.APPROVED);
        pago.setCheckoutUrl("https://mp.com/checkout");
        pago.setGatewayPaymentId("pref-123");
        pago.setErrorMessage(null);
        pago.setCreatedAt(LocalDateTime.now());

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));

        PagoResponse response = pagoService.obtenerPorId(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEstado()).isEqualTo("APPROVED");
        assertThat(response.getMoneda()).isEqualTo("PEN");
        assertThat(response.getMonto()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void obtenerPorIdThrowsEntityNotFoundWhenMissing() {
        when(pagoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pagoService.obtenerPorId(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ==================== marcarAprobado ====================

    @Test
    void marcarAprobadoTransitionsPendingToApproved() {
        Pago pago = buildPago(1L, EstadoPago.PENDING);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));
        when(pagoRepository.save(pago)).thenReturn(pago);

        pagoService.marcarAprobado(1L);

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.APPROVED);
        verify(pagoEventPublisher).publish(any(PagoEvent.class));
    }

    @Test
    void marcarAprobadoIsIdempotentWhenAlreadyApproved() {
        Pago pago = buildPago(1L, EstadoPago.APPROVED);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));

        pagoService.marcarAprobado(1L);

        verify(pagoRepository, never()).save(any());
        verify(pagoEventPublisher, never()).publish(any());
    }

    @Test
    void marcarAprobadoIsIdempotentWhenAlreadyRejected() {
        Pago pago = buildPago(1L, EstadoPago.REJECTED);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));

        pagoService.marcarAprobado(1L);

        verify(pagoRepository, never()).save(any());
        verify(pagoEventPublisher, never()).publish(any());
    }

    @Test
    void marcarAprobadoThrowsEntityNotFoundWhenPagoMissing() {
        when(pagoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pagoService.marcarAprobado(99L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ==================== marcarRechazado ====================

    @Test
    void marcarRechazadoTransitionsPendingToRejected() {
        Pago pago = buildPago(1L, EstadoPago.PENDING);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));
        when(pagoRepository.save(pago)).thenReturn(pago);

        pagoService.marcarRechazado(1L, "rejected by MP");

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.REJECTED);
        assertThat(pago.getErrorMessage()).isEqualTo("rejected by MP");
        verify(pagoEventPublisher).publish(any(PagoEvent.class));
    }

    @Test
    void marcarRechazadoIsIdempotentWhenAlreadyRejected() {
        Pago pago = buildPago(1L, EstadoPago.REJECTED);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));

        pagoService.marcarRechazado(1L, "duplicate webhook");

        verify(pagoRepository, never()).save(any());
        verify(pagoEventPublisher, never()).publish(any());
    }

    @Test
    void marcarRechazadoIsIdempotentWhenAlreadyApproved() {
        Pago pago = buildPago(1L, EstadoPago.APPROVED);

        when(pagoRepository.findById(1L)).thenReturn(Optional.of(pago));

        pagoService.marcarRechazado(1L, "late rejection");

        verify(pagoRepository, never()).save(any());
        verify(pagoEventPublisher, never()).publish(any());
    }

    @Test
    void marcarRechazadoThrowsEntityNotFoundWhenPagoMissing() {
        when(pagoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pagoService.marcarRechazado(99L, "error"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ==================== buildEvent / toResponse (covered via marcarAprobado) ====================

    @Test
    void publishedEventContainsCorrectData() {
        Pago pago = buildPago(7L, EstadoPago.PENDING);
        pago.setGatewayPaymentId(null);
        pago.setErrorMessage(null);

        when(pagoRepository.findById(7L)).thenReturn(Optional.of(pago));
        when(pagoRepository.save(pago)).thenReturn(pago);

        ArgumentCaptor<PagoEvent> captor = ArgumentCaptor.forClass(PagoEvent.class);
        pagoService.marcarAprobado(7L);

        verify(pagoEventPublisher).publish(captor.capture());
        PagoEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("PagoAprobado");
        assertThat(event.getPagoId()).isEqualTo(7L);
        assertThat(event.getReservaId()).isEqualTo(10L);
        assertThat(event.getEstado()).isEqualTo("APPROVED");
        assertThat(event.getMonto()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(event.getMoneda()).isEqualTo("PEN");
        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void toResponseIncludesNullEstadoWhenEstadoIsNull() {
        Pago pago = buildPago(3L, null);

        when(pagoRepository.findById(3L)).thenReturn(Optional.of(pago));

        PagoResponse response = pagoService.obtenerPorId(3L);

        assertThat(response.getEstado()).isNull();
    }

    // ==================== helpers ====================

    private CrearPagoRequest buildRequest(Long reservaId, BigDecimal monto) {
        CrearPagoRequest req = new CrearPagoRequest();
        req.setReservaId(reservaId);
        req.setMonto(monto);
        req.setMoneda("PEN");
        req.setDescripcion("Test pago");
        return req;
    }

    private Pago buildPago(Long id, EstadoPago estado) {
        Pago p = new Pago();
        p.setId(id);
        p.setReservaId(10L);
        p.setMonto(new BigDecimal("100.00"));
        p.setMoneda("PEN");
        p.setGateway("MERCADOPAGO");
        p.setEstado(estado);
        return p;
    }
}
