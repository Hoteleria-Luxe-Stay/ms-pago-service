package com.hotel.pago.helpers.exceptions;

import com.hotel.pago.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/api/v1/pagos");
    }

    @Test
    void handleEntityNotFoundReturns404() {
        EntityNotFoundException ex = new EntityNotFoundException("Pago", 10L);

        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo("Not Found");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/pagos");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    void handleBusinessExceptionReturns400WithErrorCode() {
        BusinessException ex = new BusinessException("operacion invalida", "OP_001");

        ResponseEntity<ErrorResponse> response = handler.handleBusiness(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo("OP_001");
        assertThat(response.getBody().getMessage()).isEqualTo("operacion invalida");
    }

    @Test
    void handleValidationExceptionReturns400() {
        ValidationException ex = new ValidationException("monto", "El monto debe ser positivo");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo("Validation Error");
        assertThat(response.getBody().getMessage()).isEqualTo("El monto debe ser positivo");
    }

    @Test
    void handleMethodArgumentNotValidReturns400() throws NoSuchMethodException {
        java.lang.reflect.Method method = DummyTarget.class.getMethod("dummy", String.class);
        MethodParameter param = new MethodParameter(method, 0);
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "reservaId", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo("Validation Failed");
    }

    @Test
    void handlePagoGatewayExceptionReturns502() {
        PagoGatewayException ex = new PagoGatewayException("MERCADOPAGO", "timeout al crear preference");

        ResponseEntity<ErrorResponse> response = handler.handlePagoGateway(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().getError()).isEqualTo("Payment Gateway Error");
        assertThat(response.getBody().getMessage()).contains("MERCADOPAGO");
    }

    @Test
    void handleWebhookValidationExceptionReturns400() {
        WebhookValidationException ex = new WebhookValidationException("HMAC invalida");

        ResponseEntity<ErrorResponse> response = handler.handleWebhookValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo("Invalid Webhook Signature");
    }

    @Test
    void handleDataIntegrityViolationReturns409() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Duplicate entry", new RuntimeException("key constraint"));

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getError()).isEqualTo("Data Integrity Violation");
    }

    @Test
    void handleOptimisticLockingReturns409() {
        ObjectOptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException(Object.class, 1L);

        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getError()).isEqualTo("Concurrent Modification");
    }

    @Test
    void handleRuntimeExceptionReturns500() {
        RuntimeException ex = new RuntimeException("unexpected crash");

        ResponseEntity<ErrorResponse> response = handler.handleRuntime(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getError()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().getMessage()).isEqualTo("unexpected crash");
    }

    // ==================== helpers ====================

    private static class DummyTarget {
        public void dummy(String arg) { /* no-op */ }
    }
}
