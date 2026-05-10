package com.hotel.pago.infrastructure.payment.mercadopago;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.pago.core.pago.ports.CheckoutSessionRequest;
import com.hotel.pago.core.pago.ports.CheckoutSessionResult;
import com.hotel.pago.core.pago.ports.PaymentGateway;
import com.hotel.pago.core.pago.ports.WebhookEventResult;
import com.hotel.pago.helpers.exceptions.PagoGatewayException;
import com.hotel.pago.helpers.exceptions.WebhookValidationException;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adaptador de MercadoPago Checkout Pro implementando {@link PaymentGateway}.
 *
 * <h3>Diferencias clave vs Stripe (por las que existe esta clase)</h3>
 * <ul>
 *   <li><b>Webhook minimalista</b>: MP envia solo {type, action, data:{id}} —
 *       el id es el paymentId, NO el preferenceId. Para conocer el estado real
 *       hay que hacer GET /v1/payments/{id} contra la API de MP.</li>
 *   <li><b>Correlacion</b>: el paymentId del webhook es DISTINTO del preferenceId
 *       que persistimos al crear. Solucion: pasar {@code external_reference =
 *       pagoId} en el preference. MP propaga ese campo al payment, y al recibir
 *       el webhook lo leemos para resolver el {@code pagoId} interno.</li>
 *   <li><b>HMAC</b>: header {@code x-signature} formato {@code ts=...,v1=hex}.
 *       Template canonico: {@code id:{data.id};request-id:{x-request-id};ts:{ts};}
 *       firmado con HMAC-SHA256. Comparacion en tiempo constante para evitar
 *       timing attacks.</li>
 *   <li><b>Sandbox vs prod URL</b>: en test mode, MP devuelve {@code sandboxInitPoint}
 *       (la URL real de pago en sandbox). En prod, {@code initPoint}.</li>
 * </ul>
 *
 * <h3>Estados de payment de MP que mapeamos</h3>
 * <pre>
 *   approved                                 → PAYMENT_APPROVED
 *   rejected | cancelled | refunded |
 *   charged_back                             → PAYMENT_REJECTED
 *   pending | in_process | authorized        → IGNORED (esperamos el webhook final)
 * </pre>
 */
public class MercadoPagoGateway implements PaymentGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(MercadoPagoGateway.class);
    private static final String GATEWAY_NAME = "MERCADOPAGO";

    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_REFUNDED = "refunded";
    private static final String STATUS_CHARGED_BACK = "charged_back";

    private final String webhookSecret;
    private final String notificationUrl;
    private final boolean useSandbox;
    private final boolean trustPayloadInSandbox;
    private final ObjectMapper objectMapper;
    private final PreferenceClient preferenceClient;
    private final PaymentClient paymentClient;

    public MercadoPagoGateway(String webhookSecret,
                              String notificationUrl,
                              boolean useSandbox,
                              boolean trustPayloadInSandbox,
                              ObjectMapper objectMapper) {
        this.webhookSecret = webhookSecret;
        this.notificationUrl = notificationUrl;
        this.useSandbox = useSandbox;
        this.trustPayloadInSandbox = trustPayloadInSandbox;
        this.objectMapper = objectMapper;
        this.preferenceClient = new PreferenceClient();
        this.paymentClient = new PaymentClient();
    }

    @Override
    public String getGatewayName() {
        return GATEWAY_NAME;
    }

    // =========================================================================
    //  CREATE CHECKOUT SESSION  →  Preference de MercadoPago
    // =========================================================================

    @Override
    public CheckoutSessionResult createCheckoutSession(CheckoutSessionRequest req) {
        try {
            // pagoId del metadata se usa como external_reference para correlacionar
            // el webhook (que trae paymentId, NO preferenceId) con el Pago en BD.
            String pagoIdReference = req.metadata() != null
                    ? req.metadata().get("pagoId")
                    : null;
            if (pagoIdReference == null || pagoIdReference.isBlank()) {
                throw new PagoGatewayException(GATEWAY_NAME,
                        "metadata.pagoId es requerido para correlacionar el webhook");
            }

            PreferenceItemRequest item = PreferenceItemRequest.builder()
                    .title(req.description())
                    .quantity(1)
                    .currencyId(req.currency().toUpperCase(Locale.ROOT))
                    .unitPrice(req.amount())
                    .build();

            PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                    .success(req.successUrl())
                    .failure(req.cancelUrl())
                    .pending(req.successUrl())
                    .build();

            PreferenceRequest.PreferenceRequestBuilder builder = PreferenceRequest.builder()
                    .items(List.of(item))
                    .backUrls(backUrls)
                    .externalReference(pagoIdReference)
                    .notificationUrl(notificationUrl);

            // auto_return="approved" hace que MP redirija automaticamente al
            // success_url despues de pagar. MP RECHAZA preference si las
            // back_urls son localhost — por eso solo lo activamos cuando
            // tenemos HTTPS valido (sirve tanto en prod como en sandbox con
            // back_urls publicas).
            String successUrlForCheck = req.successUrl();
            boolean backUrlsAreHttps = successUrlForCheck != null
                    && successUrlForCheck.startsWith("https://");
            if (backUrlsAreHttps) {
                builder.autoReturn("approved");
            }

            if (req.metadata() != null && !req.metadata().isEmpty()) {
                builder.metadata(new HashMap<>(req.metadata()));
            }

            Preference preference = preferenceClient.create(builder.build());

            // sandboxInitPoint = URL del checkout sandbox (test). initPoint = URL
            // del checkout prod. La eleccion es EXPLICITA via flag, no implicita
            // ("usa la que MP me devuelva no-null"): un access token de prod que
            // por error devuelva sandboxInitPoint mandaria a tus clientes a un
            // checkout fake — sin cobro real, sin webhook, sin nada.
            String checkoutUrl = useSandbox
                    ? preference.getSandboxInitPoint()
                    : preference.getInitPoint();
            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                throw new PagoGatewayException(GATEWAY_NAME,
                        "MP no devolvio " + (useSandbox ? "sandboxInitPoint" : "initPoint")
                                + " — verificar que el access token corresponde al modo configurado (use-sandbox=" + useSandbox + ")");
            }

            LOGGER.info("Preference creada: id={}, externalReference={}",
                    preference.getId(), pagoIdReference);

            return new CheckoutSessionResult(preference.getId(), checkoutUrl);

        } catch (MPApiException e) {
            String body = e.getApiResponse() != null ? e.getApiResponse().getContent() : "(sin body)";
            throw new PagoGatewayException(GATEWAY_NAME,
                    "Error MP API creando preference: " + body, e);
        } catch (MPException e) {
            throw new PagoGatewayException(GATEWAY_NAME,
                    "Error MP creando preference: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    //  VERIFY + RESOLVE WEBHOOK
    // =========================================================================

    @Override
    public WebhookEventResult verifyAndResolveWebhook(String payload, Map<String, String> headers) {
        LOGGER.warn("[DIAG-WEBHOOK] payload={} headers={}", payload, headers);

        String dataId = extractDataId(payload);
        if (dataId == null || dataId.isBlank()) {
            LOGGER.info("Webhook MP sin data.id; ignorando");
            return WebhookEventResult.ignored();
        }

        String type = extractType(payload);
        if (!"payment".equalsIgnoreCase(type)) {
            LOGGER.info("Webhook MP type={} no es payment; ignorando", type);
            return WebhookEventResult.ignored();
        }

        // Quirk no documentado de MP: en sandbox, los webhooks reales del TESTUSER
        // OFICIAL llegan con live_mode=true y son firmados con un secret distinto
        // al mostrado en el dashboard. Bypass HMAC SOLO si flag explicita esta on
        // y use-sandbox=true. paymentClient.get() sigue ejecutandose contra MP API,
        // por lo que un payload spoofeado con paymentId inventado da 404 -> IGNORED.
        if (useSandbox && trustPayloadInSandbox) {
            LOGGER.warn("[SANDBOX-BYPASS] Saltando validacion HMAC. Solo usar en TEST. NO USAR EN PROD.");
        } else {
            String signatureHeader = caseInsensitiveGet(headers, "x-signature");
            String requestId = caseInsensitiveGet(headers, "x-request-id");
            if (signatureHeader == null || requestId == null) {
                throw new WebhookValidationException(
                        "Headers requeridos ausentes: x-signature y/o x-request-id");
            }
            verifyHmac(signatureHeader, requestId, dataId);
        }

        return resolvePaymentStatus(dataId);
    }

    private WebhookEventResult resolvePaymentStatus(String paymentIdStr) {
        try {
            Long paymentId = Long.parseLong(paymentIdStr);
            Payment payment = paymentClient.get(paymentId);

            String externalReference = payment.getExternalReference();
            if (externalReference == null || externalReference.isBlank()) {
                LOGGER.warn("Payment {} sin externalReference; no podemos correlacionar", paymentId);
                return WebhookEventResult.ignored();
            }

            Long pagoId;
            try {
                pagoId = Long.parseLong(externalReference);
            } catch (NumberFormatException nfe) {
                LOGGER.error("externalReference no es Long parseable: {}", externalReference);
                return WebhookEventResult.ignored();
            }

            String status = payment.getStatus();
            String detail = payment.getStatusDetail();
            LOGGER.info("Payment {} (pagoId={}) status={} detail={}",
                    paymentId, pagoId, status, detail);

            return mapStatusToResult(pagoId, status, detail);

        } catch (NumberFormatException e) {
            LOGGER.error("data.id no es Long parseable: {}", paymentIdStr);
            return WebhookEventResult.ignored();
        } catch (MPApiException e) {
            String body = e.getApiResponse() != null ? e.getApiResponse().getContent() : "(sin body)";
            throw new PagoGatewayException(GATEWAY_NAME,
                    "Error MP API obteniendo payment " + paymentIdStr + ": " + body, e);
        } catch (MPException e) {
            throw new PagoGatewayException(GATEWAY_NAME,
                    "Error MP obteniendo payment " + paymentIdStr + ": " + e.getMessage(), e);
        }
    }

    private WebhookEventResult mapStatusToResult(Long pagoId, String status, String detail) {
        if (status == null) {
            return WebhookEventResult.ignored();
        }
        return switch (status) {
            case STATUS_APPROVED -> WebhookEventResult.approved(pagoId);
            case STATUS_REJECTED, STATUS_CANCELLED, STATUS_REFUNDED, STATUS_CHARGED_BACK ->
                    WebhookEventResult.rejected(pagoId,
                            "MP status=" + status + (detail != null ? " detail=" + detail : ""));
            default -> {
                // pending, in_process, authorized: aun no es terminal, esperamos otro webhook
                LOGGER.info("Payment pagoId={} estado intermedio {}, ignorando", pagoId, status);
                yield WebhookEventResult.ignored();
            }
        };
    }

    // =========================================================================
    //  HMAC verification (x-signature)
    // =========================================================================

    private void verifyHmac(String signatureHeader, String requestId, String dataId) {
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0].trim()) {
                case "ts" -> ts = kv[1].trim();
                case "v1" -> v1 = kv[1].trim();
                default -> { /* ignorar otros campos */ }
            }
        }
        if (ts == null || v1 == null) {
            throw new WebhookValidationException(
                    "x-signature mal formado (ts o v1 ausente): " + signatureHeader);
        }

        String template = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        String calculated = hmacSha256Hex(webhookSecret, template);

        boolean match = constantTimeEquals(calculated, v1);
        String secretFingerprint = (webhookSecret == null || webhookSecret.length() < 16)
                ? "BAD"
                : webhookSecret.substring(0, 8) + "..." + webhookSecret.substring(webhookSecret.length() - 8);
        LOGGER.warn("[DIAG-HMAC] sig='{}' reqId='{}' dataId='{}' ts='{}' template='{}' v1Recv='{}' v1Calc='{}' match={} secretLen={} secretFingerprint='{}'",
                signatureHeader, requestId, dataId, ts, template, v1, calculated, match,
                webhookSecret == null ? -1 : webhookSecret.length(), secretFingerprint);

        if (!match) {
            throw new WebhookValidationException("Firma HMAC invalida");
        }
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Fallo computando HMAC-SHA256", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    //  JSON helpers (Jackson)
    // =========================================================================

    private String extractDataId(String payload) {
        JsonNode root = parseJson(payload);
        if (root == null) return null;
        JsonNode data = root.get("data");
        if (data == null || !data.has("id")) return null;
        return data.get("id").asText(null);
    }

    private String extractType(String payload) {
        JsonNode root = parseJson(payload);
        if (root == null) return null;
        return root.has("type") ? root.get("type").asText(null) : null;
    }

    private JsonNode parseJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            LOGGER.warn("No se pudo parsear payload de webhook MP: {}", e.getMessage());
            return null;
        }
    }

    private static String caseInsensitiveGet(Map<String, String> headers, String key) {
        if (headers == null) return null;
        String direct = headers.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }
}
