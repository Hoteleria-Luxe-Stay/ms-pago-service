package com.hotel.pago.infrastructure.payment.mercadopago;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.pago.core.pago.ports.PaymentGateway;
import com.mercadopago.MercadoPagoConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring de MercadoPago: setea el access token global del SDK al arrancar y
 * expone el {@link PaymentGateway} como bean Spring.
 *
 * El SDK de MP usa un singleton estatico {@code MercadoPagoConfig.setAccessToken()}
 * que cualquier {@code PaymentClient} / {@code PreferenceClient} lee al construirse.
 * Por eso la inicializacion va en {@code @PostConstruct} y los clients se crean
 * dentro del gateway sin parametros.
 */
@Configuration
public class MercadoPagoBeans {

    private static final Logger LOGGER = LoggerFactory.getLogger(MercadoPagoBeans.class);

    @Value("${mercadopago.access-token}")
    private String accessToken;

    @Value("${mercadopago.webhook-secret}")
    private String webhookSecret;

    @Value("${mercadopago.notification-url}")
    private String notificationUrl;

    @Value("${mercadopago.use-sandbox:false}")
    private boolean useSandbox;

    @Value("${mercadopago.webhook.trust-payload-in-sandbox:false}")
    private boolean trustPayloadInSandbox;

    @PostConstruct
    public void init() {
        if (accessToken == null || accessToken.isBlank() || accessToken.contains("REPLACE_ME")) {
            LOGGER.warn("MERCADOPAGO_ACCESS_TOKEN no esta configurado. ms-pago no podra crear preferences.");
            return;
        }
        MercadoPagoConfig.setAccessToken(accessToken);
        LOGGER.info("MercadoPago SDK configurado. useSandbox={} notificationUrl={}",
                useSandbox, notificationUrl);
    }

    @Bean
    public PaymentGateway paymentGateway(ObjectMapper objectMapper) {
        return new MercadoPagoGateway(webhookSecret, notificationUrl, useSandbox, trustPayloadInSandbox, objectMapper);
    }
}
