package com.hotel.pago.infrastructure.payment.mercadopago;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.pago.core.pago.ports.CheckoutSessionRequest;
import com.hotel.pago.core.pago.ports.WebhookEventResult;
import com.hotel.pago.core.pago.ports.WebhookEventType;
import com.hotel.pago.helpers.exceptions.PagoGatewayException;
import com.hotel.pago.helpers.exceptions.WebhookValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests para MercadoPagoGateway.
 *
 * - Los metodos publicos que llaman al SDK de MP (preferenceClient.create, paymentClient.get)
 *   no se pueden mockear facilmente sin PowerMock ya que el SDK instancia esos clientes internamente.
 *   Por eso testeamos:
 *   1. Validaciones previas a la llamada SDK (pagoId ausente → PagoGatewayException)
 *   2. Fallback methods directamente via ReflectionTestUtils (logica critica de CB)
 *   3. Metodos privados de parsing/HMAC via reflection
 *   4. verifyAndResolveWebhook con sandbox bypass y rutas que no requieren SDK call
 */
class MercadoPagoGatewayTest {

    private static final String SECRET = "test-webhook-secret-1234567890ab";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MercadoPagoGateway gateway;
    private MercadoPagoGateway sandboxBypassGateway;
    private MercadoPagoGateway prodGateway;

    @BeforeEach
    void setUp() {
        // Gateway con HMAC real (sandbox=false, trustPayload=false) — validacion HMAC activa
        gateway = new MercadoPagoGateway(SECRET, "https://notify.example.com", false, false, OBJECT_MAPPER);
        // Gateway sandbox con bypass habilitado (evita paymentClient.get para tests)
        sandboxBypassGateway = new MercadoPagoGateway(SECRET, "https://notify.example.com", true, true, OBJECT_MAPPER);
        // Gateway prod (sandbox=false)
        prodGateway = new MercadoPagoGateway(SECRET, "https://notify.example.com", false, false, OBJECT_MAPPER);
    }

    // ==================== getGatewayName ====================

    @Test
    void getGatewayNameReturnsMercadopago() {
        assertThat(gateway.getGatewayName()).isEqualTo("MERCADOPAGO");
    }

    // ==================== createCheckoutSession — validaciones previas al SDK ====================

    @Test
    void createCheckoutSessionThrowsPagoGatewayExceptionWhenPagoIdMissingFromMetadata() {
        CheckoutSessionRequest req = new CheckoutSessionRequest(
                new BigDecimal("100.00"),
                "PEN",
                "Test item",
                Map.of("reservaId", "42"), // sin pagoId
                "https://success.com",
                "https://cancel.com"
        );

        assertThatThrownBy(() -> gateway.createCheckoutSession(req))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("pagoId");
    }

    @Test
    void createCheckoutSessionThrowsPagoGatewayExceptionWhenMetadataIsNull() {
        CheckoutSessionRequest req = new CheckoutSessionRequest(
                new BigDecimal("100.00"),
                "PEN",
                "Test item",
                null, // metadata null
                "https://success.com",
                "https://cancel.com"
        );

        assertThatThrownBy(() -> gateway.createCheckoutSession(req))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("pagoId");
    }

    @Test
    void createCheckoutSessionThrowsPagoGatewayExceptionWhenPagoIdIsBlank() {
        CheckoutSessionRequest req = new CheckoutSessionRequest(
                new BigDecimal("100.00"),
                "PEN",
                "Test item",
                Map.of("pagoId", "   "), // blank pagoId
                "https://success.com",
                "https://cancel.com"
        );

        assertThatThrownBy(() -> gateway.createCheckoutSession(req))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("pagoId");
    }

    // ==================== fallbackCreateCheckoutSession ====================

    @Test
    void fallbackCreateCheckoutSessionAlwaysThrowsPagoGatewayException() {
        CheckoutSessionRequest req = new CheckoutSessionRequest(
                BigDecimal.TEN, "PEN", "test", Map.of("pagoId", "1"),
                "https://s.com", "https://c.com"
        );
        RuntimeException cause = new RuntimeException("MP timeout");

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(gateway, "fallbackCreateCheckoutSession", req, cause))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("MercadoPago no disponible");
    }

    @Test
    void fallbackCreateCheckoutSessionWrapsCauseInPagoGatewayException() {
        CheckoutSessionRequest req = new CheckoutSessionRequest(
                BigDecimal.TEN, "PEN", "test", Map.of("pagoId", "1"),
                "https://s.com", "https://c.com"
        );
        RuntimeException cause = new RuntimeException("network error");

        try {
            ReflectionTestUtils.invokeMethod(gateway, "fallbackCreateCheckoutSession", req, cause);
        } catch (PagoGatewayException ex) {
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getGateway()).isEqualTo("MERCADOPAGO");
        }
    }

    // ==================== fallbackVerifyAndResolveWebhook — CRITICAL BRANCH ====================

    @Test
    void fallbackVerifyAndResolveWebhookRethrowsWebhookValidationExceptionAsIs() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}";
        Map<String, String> headers = Map.of("x-signature", "ts=123,v1=abc");
        WebhookValidationException hmacError = new WebhookValidationException("Firma HMAC invalida");

        // El branch critico: instanceof WebhookValidationException → re-throw SIN envolver
        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(gateway, "fallbackVerifyAndResolveWebhook",
                        payload, headers, hmacError))
                .isInstanceOf(WebhookValidationException.class)
                .isSameAs(hmacError) // misma instancia, no envuelta
                .hasMessage("Firma HMAC invalida");
    }

    @Test
    void fallbackVerifyAndResolveWebhookConvertsPagoGatewayExceptionToPagoGatewayException() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}";
        Map<String, String> headers = Map.of();
        PagoGatewayException downstreamError = new PagoGatewayException("MERCADOPAGO", "MP caida");

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(gateway, "fallbackVerifyAndResolveWebhook",
                        payload, headers, downstreamError))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("webhook sera reintentado");
    }

    @Test
    void fallbackVerifyAndResolveWebhookConvertsRuntimeExceptionToPagoGatewayException() {
        String payload = "{}";
        Map<String, String> headers = Map.of();
        RuntimeException generic = new RuntimeException("connection refused");

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(gateway, "fallbackVerifyAndResolveWebhook",
                        payload, headers, generic))
                .isInstanceOf(PagoGatewayException.class)
                .hasMessageContaining("No se pudo resolver estado")
                .extracting(ex -> ((PagoGatewayException) ex).getCause())
                .isSameAs(generic);
    }

    // ==================== verifyAndResolveWebhook — rutas sin SDK call ====================

    @Test
    void verifyAndResolveWebhookReturnsIgnoredWhenPayloadHasNoDataId() {
        String payload = "{\"type\":\"payment\",\"data\":{}}";

        WebhookEventResult result = sandboxBypassGateway.verifyAndResolveWebhook(payload, Map.of());

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
        assertThat(result.pagoId()).isNull();
    }

    @Test
    void verifyAndResolveWebhookReturnsIgnoredWhenTypeIsNotPayment() {
        String payload = "{\"type\":\"merchant_order\",\"data\":{\"id\":\"123\"}}";

        WebhookEventResult result = sandboxBypassGateway.verifyAndResolveWebhook(payload, Map.of());

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void verifyAndResolveWebhookReturnsIgnoredWhenPayloadIsInvalidJson() {
        String payload = "not-json-at-all";

        WebhookEventResult result = sandboxBypassGateway.verifyAndResolveWebhook(payload, Map.of());

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void verifyAndResolveWebhookThrowsWebhookValidationExceptionWhenSignatureHeadersMissing() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}";
        Map<String, String> headers = Map.of(); // no x-signature, no x-request-id

        assertThatThrownBy(() -> gateway.verifyAndResolveWebhook(payload, headers))
                .isInstanceOf(WebhookValidationException.class)
                .hasMessageContaining("Headers requeridos ausentes");
    }

    @Test
    void verifyAndResolveWebhookThrowsWhenOnlySignaturePresentButNoRequestId() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}";
        Map<String, String> headers = Map.of("x-signature", "ts=1,v1=abc");

        assertThatThrownBy(() -> gateway.verifyAndResolveWebhook(payload, headers))
                .isInstanceOf(WebhookValidationException.class)
                .hasMessageContaining("Headers requeridos ausentes");
    }

    @Test
    void verifyAndResolveWebhookThrowsWhenHmacIsInvalid() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"456\"}}";
        Map<String, String> headers = Map.of(
                "x-signature", "ts=1700000000,v1=badhash000000000000000000000000000000000000000000000000000000000",
                "x-request-id", "req-111"
        );

        assertThatThrownBy(() -> gateway.verifyAndResolveWebhook(payload, headers))
                .isInstanceOf(WebhookValidationException.class)
                .hasMessageContaining("HMAC invalida");
    }

    @Test
    void verifyAndResolveWebhookThrowsWhenSignatureHeaderMalformed() {
        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"456\"}}";
        Map<String, String> headers = Map.of(
                "x-signature", "malformed-no-ts-v1",
                "x-request-id", "req-222"
        );

        assertThatThrownBy(() -> gateway.verifyAndResolveWebhook(payload, headers))
                .isInstanceOf(WebhookValidationException.class)
                .hasMessageContaining("x-signature mal formado");
    }

    // ==================== verifyAndResolveWebhook — valid HMAC (no SDK call needed for parsing) ====================

    @Test
    void verifyHmacDoesNotThrowWhenSignatureIsValid() throws Exception {
        // This constructs a valid HMAC so that verifyHmac passes, then fails at paymentClient.get
        // (which we can't mock). We verify the HMAC validation itself doesn't throw.
        String dataId = "789";
        String requestId = "req-valid";
        String ts = "1700000000";
        String template = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        String validHmac = computeHmac(SECRET, template);

        String payload = "{\"type\":\"payment\",\"data\":{\"id\":\"" + dataId + "\"}}";
        Map<String, String> headers = Map.of(
                "x-signature", "ts=" + ts + ",v1=" + validHmac,
                "x-request-id", requestId
        );

        // With a valid HMAC, we get past verifyHmac but then fall into resolvePaymentStatus
        // which calls paymentClient.get(789L) - this will throw an SDK exception
        // We just want to ensure WebhookValidationException is NOT thrown (HMAC is valid)
        try {
            gateway.verifyAndResolveWebhook(payload, headers);
        } catch (WebhookValidationException e) {
            // HMAC-related exception — test fails
            throw new AssertionError("Should not throw WebhookValidationException for valid HMAC", e);
        } catch (PagoGatewayException e) {
            // Expected — SDK call fails (no real MP connection in unit test)
            assertThat(e.getGateway()).isEqualTo("MERCADOPAGO");
        } catch (Exception e) {
            // Any other exception from the SDK is acceptable for this test
        }
    }

    // ==================== caseInsensitiveGet (via reflection) ====================

    @Test
    void caseInsensitiveGetFindsHeaderWithExactKey() {
        Map<String, String> headers = Map.of("x-signature", "value123");

        String result = ReflectionTestUtils.invokeMethod(gateway, "caseInsensitiveGet", headers, "x-signature");

        assertThat(result).isEqualTo("value123");
    }

    @Test
    void caseInsensitiveGetFindsHeaderWithDifferentCase() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Signature", "cased-value");

        String result = ReflectionTestUtils.invokeMethod(gateway, "caseInsensitiveGet", headers, "x-signature");

        assertThat(result).isEqualTo("cased-value");
    }

    @Test
    void caseInsensitiveGetReturnsNullWhenHeaderAbsent() {
        Map<String, String> headers = Map.of("x-other", "val");

        String result = ReflectionTestUtils.invokeMethod(gateway, "caseInsensitiveGet", headers, "x-signature");

        assertThat(result).isNull();
    }

    @Test
    void caseInsensitiveGetReturnsNullWhenHeadersMapIsNull() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "caseInsensitiveGet", (Map<String, String>) null, "x-signature");

        assertThat(result).isNull();
    }

    // ==================== parseJson / extractDataId / extractType (via reflection) ====================

    @Test
    void parseJsonReturnsNullForInvalidJson() {
        Object result = ReflectionTestUtils.invokeMethod(gateway, "parseJson", "not-json");
        assertThat(result).isNull();
    }

    @Test
    void extractDataIdReturnsNullWhenPayloadHasNoDataField() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "extractDataId", "{\"type\":\"payment\"}");
        assertThat(result).isNull();
    }

    @Test
    void extractDataIdReturnsNullWhenDataHasNoId() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "extractDataId", "{\"data\":{}}");
        assertThat(result).isNull();
    }

    @Test
    void extractDataIdReturnsIdWhenPresent() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "extractDataId",
                "{\"data\":{\"id\":\"123456\"}}");
        assertThat(result).isEqualTo("123456");
    }

    @Test
    void extractTypeReturnsNullWhenPayloadHasNoTypeField() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "extractType", "{\"data\":{}}");
        assertThat(result).isNull();
    }

    @Test
    void extractTypeReturnsTypeWhenPresent() {
        String result = ReflectionTestUtils.invokeMethod(gateway, "extractType",
                "{\"type\":\"payment\",\"data\":{\"id\":\"1\"}}");
        assertThat(result).isEqualTo("payment");
    }

    // ==================== mapStatusToResult (via reflection) ====================

    @Test
    void mapStatusToResultApprovedReturnsPaymentApproved() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "approved", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.PAYMENT_APPROVED);
        assertThat(result.pagoId()).isEqualTo(10L);
    }

    @Test
    void mapStatusToResultRejectedReturnsPaymentRejected() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "rejected", "cc_rejected_bad_filled_card_number");

        assertThat(result.type()).isEqualTo(WebhookEventType.PAYMENT_REJECTED);
        assertThat(result.pagoId()).isEqualTo(10L);
        assertThat(result.errorMessage()).contains("rejected");
    }

    @Test
    void mapStatusToResultCancelledReturnsPaymentRejected() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "cancelled", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.PAYMENT_REJECTED);
    }

    @Test
    void mapStatusToResultRefundedReturnsPaymentRejected() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "refunded", "refund");

        assertThat(result.type()).isEqualTo(WebhookEventType.PAYMENT_REJECTED);
    }

    @Test
    void mapStatusToResultChargedBackReturnsPaymentRejected() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "charged_back", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.PAYMENT_REJECTED);
    }

    @Test
    void mapStatusToResultPendingReturnsIgnored() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "pending", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void mapStatusToResultInProcessReturnsIgnored() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "in_process", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void mapStatusToResultAuthorizedReturnsIgnored() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, "authorized", null);

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void mapStatusToResultNullStatusReturnsIgnored() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 10L, (String) null, null);

        assertThat(result.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void mapStatusToResultRejectedWithNullDetailOmitsDetailInMessage() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 5L, "rejected", null);

        assertThat(result.errorMessage()).doesNotContain("detail=");
    }

    @Test
    void mapStatusToResultRejectedWithDetailIncludesDetailInMessage() {
        WebhookEventResult result = ReflectionTestUtils.invokeMethod(
                gateway, "mapStatusToResult", 5L, "rejected", "insufficient_funds");

        assertThat(result.errorMessage()).contains("detail=insufficient_funds");
    }

    // ==================== WebhookEventResult static factories ====================

    @Test
    void webhookEventResultApprovedFactory() {
        WebhookEventResult r = WebhookEventResult.approved(42L);
        assertThat(r.type()).isEqualTo(WebhookEventType.PAYMENT_APPROVED);
        assertThat(r.pagoId()).isEqualTo(42L);
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void webhookEventResultRejectedFactory() {
        WebhookEventResult r = WebhookEventResult.rejected(42L, "bad card");
        assertThat(r.type()).isEqualTo(WebhookEventType.PAYMENT_REJECTED);
        assertThat(r.errorMessage()).isEqualTo("bad card");
    }

    @Test
    void webhookEventResultIgnoredFactory() {
        WebhookEventResult r = WebhookEventResult.ignored();
        assertThat(r.type()).isEqualTo(WebhookEventType.IGNORED);
        assertThat(r.pagoId()).isNull();
    }

    // ==================== helpers ====================

    private static String computeHmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
