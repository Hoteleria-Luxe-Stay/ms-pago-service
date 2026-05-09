package com.hotel.pago.api;

import com.hotel.pago.core.pago.ports.PaymentGateway;
import com.hotel.pago.core.pago.ports.WebhookEventResult;
import com.hotel.pago.core.pago.service.PagoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint publico (sin JWT) que recibe webhooks del proveedor de pago.
 *
 * El controller NO conoce al proveedor — delega TODO al {@link PaymentGateway}:
 * verificacion HMAC, parsing del payload, consulta de estado al proveedor (si
 * el webhook no lo trae) y mapeo a un evento normalizado. Cambiar de proveedor
 * es agregar otra implementacion del puerto y, eventualmente, otro endpoint.
 *
 * Path: {@code /pagos/webhook/mercadopago}. La validacion de autenticidad es
 * por HMAC dentro del gateway (firma {@code x-signature}). Si la firma falla,
 * el {@link com.hotel.pago.helpers.exceptions.WebhookValidationException} se
 * traduce a 400 en el {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/pagos/webhook")
public class WebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentGateway paymentGateway;
    private final PagoService pagoService;

    public WebhookController(PaymentGateway paymentGateway, PagoService pagoService) {
        this.paymentGateway = paymentGateway;
        this.pagoService = pagoService;
    }

    @PostMapping(value = "/mercadopago", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> handleMercadoPagoWebhook(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {

        WebhookEventResult result = paymentGateway.verifyAndResolveWebhook(payload, headers);
        LOGGER.info("Webhook {} resuelto: type={} pagoId={}",
                paymentGateway.getGatewayName(), result.type(), result.pagoId());

        switch (result.type()) {
            case PAYMENT_APPROVED -> pagoService.marcarAprobado(result.pagoId());
            case PAYMENT_REJECTED -> pagoService.marcarRechazado(result.pagoId(), result.errorMessage());
            case IGNORED -> { /* no-op: evento valido pero no terminal */ }
        }

        // El proveedor espera 2xx; cualquier otro status hace que reenvie el webhook.
        return ResponseEntity.ok().build();
    }
}
