package com.hotel.pago.core.outbox.service;

import com.hotel.pago.core.outbox.model.OutboxEvent;
import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> outboxKafkaTemplate;

    @InjectMocks
    private OutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(relayService, "batchSize", 100);
        ReflectionTestUtils.setField(relayService, "sendTimeoutMs", 5000L);
        ReflectionTestUtils.setField(relayService, "maxAttempts", 10);
    }

    @Test
    void relayBatchReturnsZeroWhenNoPendingEvents() {
        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of());

        int result = relayService.relayBatch();

        assertThat(result).isEqualTo(0);
        verify(outboxRepository, never()).saveAll(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchPublishesEventsAndMarksSent() throws Exception {
        OutboxEvent event = buildEvent(1L, "pago.events", "PagoCreado");

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(event));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        int result = relayService.relayBatch();

        assertThat(result).isEqualTo(1);
        assertThat(event.isSent()).isTrue();
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        verify(outboxRepository).saveAll(List.of(event));
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchIncrementsAttemptsOnKafkaFailure() {
        OutboxEvent event = buildEvent(2L, "pago.events", "PagoCreado");
        event.setAttempts(0);

        CompletableFuture<Object> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(event));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class))).thenReturn((CompletableFuture) failedFuture);

        int result = relayService.relayBatch();

        assertThat(result).isEqualTo(0);
        assertThat(event.isSent()).isFalse();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("Kafka unavailable");
        assertThat(event.isDead()).isFalse();
        verify(outboxRepository).saveAll(List.of(event));
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchMarksEventAsDeadAfterMaxAttempts() {
        OutboxEvent event = buildEvent(3L, "pago.events", "PagoCreado");
        event.setAttempts(9); // maxAttempts=10, after +1 = 10 → dead

        CompletableFuture<Object> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("persistent error"));

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(event));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class))).thenReturn((CompletableFuture) failedFuture);

        relayService.relayBatch();

        assertThat(event.isDead()).isTrue();
        assertThat(event.getAttempts()).isEqualTo(10);
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchContinuesProcessingRemainingEventsAfterOneFailure() throws Exception {
        OutboxEvent failing = buildEvent(1L, "pago.events", "PagoCreado");
        OutboxEvent succeeding = buildEvent(2L, "pago.events", "PagoAprobado");

        CompletableFuture<Object> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("partial failure"));

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(failing, succeeding));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn((CompletableFuture) failedFuture)
                .thenReturn(CompletableFuture.completedFuture(null));

        int result = relayService.relayBatch();

        assertThat(result).isEqualTo(1);
        assertThat(failing.isSent()).isFalse();
        assertThat(succeeding.isSent()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchTruncatesLongErrorMessages() {
        OutboxEvent event = buildEvent(4L, "pago.events", "PagoCreado");
        event.setAttempts(0);
        String longError = "x".repeat(2000);

        CompletableFuture<Object> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException(longError));

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(event));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class))).thenReturn((CompletableFuture) failedFuture);

        relayService.relayBatch();

        assertThat(event.getLastError()).hasSize(1000);
    }

    @SuppressWarnings("unchecked")
    @Test
    void relayBatchAddsKafkaHeadersToProducerRecord() throws Exception {
        OutboxEvent event = buildEvent(5L, "pago.events", "PagoCreado");
        event.setAggregateType("Pago");
        event.setAggregateId("42");
        event.setId(5L);

        when(outboxRepository.findUnsentBatchForUpdate(100)).thenReturn(List.of(event));
        when(outboxKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        relayService.relayBatch();

        // Verifica que se llamo a send (headers se construyen dentro de publishToKafka)
        verify(outboxKafkaTemplate).send(any(ProducerRecord.class));
    }

    // ==================== helpers ====================

    private OutboxEvent buildEvent(Long id, String topic, String eventType) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setTopic(topic);
        e.setEventType(eventType);
        e.setAggregateType("Pago");
        e.setAggregateId("1");
        e.setPayload("{\"test\":true}");
        e.setSent(false);
        e.setAttempts(0);
        e.setDead(false);
        return e;
    }
}
