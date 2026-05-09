package com.hotel.pago.infrastructure.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Producer Kafka dedicado al relay del outbox (Round 6).
 *
 * Configuracion:
 *  - {@code enable.idempotence=true} → el broker garantiza que reintentos del
 *    producer NO crean duplicados (cada producer tiene un PID + sequence number).
 *  - {@code acks=all} → el broker espera que el leader y los ISR replicas hayan
 *    persistido el record antes de ack.
 *  - {@code max.in.flight.requests.per.connection=5} → maximo permitido manteniendo
 *    idempotencia. Mejor throughput sin perder garantias.
 *  - {@code retries=Integer.MAX_VALUE} → reintenta indefinidamente; si Kafka esta
 *    caido el .get() del relay timeoutea, hace rollback de la tx, y el evento queda
 *    unsent para el proximo tick.
 *
 * Por que un producer dedicado: el relay manda payloads ya serializados a JSON
 * (String), no objetos. No queremos que se serialicen dos veces. Ademas asi no
 * pisamos la config Kafka que pueda estar en application.yml para otros usos.
 */
@Configuration
public class KafkaConfig {

    private final KafkaProperties kafkaProperties;

    public KafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ProducerFactory<String, String> outboxProducerFactory() {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Idempotent + at-least-once garantizado a nivel broker
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // El timeout total de un .send() (incluye retries internos)
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
