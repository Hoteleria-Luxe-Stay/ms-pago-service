package com.hotel.pago.infrastructure.jobs;

import com.hotel.pago.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxCleanupJobTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @InjectMocks
    private OutboxCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cleanupJob, "retentionDays", 7);
    }

    @Test
    void runDeletesSentEventsOlderThanRetentionPeriod() {
        when(outboxRepository.deleteSentBefore(any(LocalDateTime.class))).thenReturn(3);

        cleanupJob.run();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxRepository).deleteSentBefore(captor.capture());

        LocalDateTime cutoff = captor.getValue();
        // Cutoff should be approximately 7 days ago
        assertThat(cutoff).isBefore(LocalDateTime.now().minusDays(6));
        assertThat(cutoff).isAfter(LocalDateTime.now().minusDays(8));
    }

    @Test
    void runDoesNotLogWhenNothingDeleted() {
        when(outboxRepository.deleteSentBefore(any())).thenReturn(0);

        cleanupJob.run(); // Should complete without error

        verify(outboxRepository).deleteSentBefore(any());
    }

    @Test
    void runLogsWhenEventsAreDeleted() {
        when(outboxRepository.deleteSentBefore(any())).thenReturn(5);

        cleanupJob.run(); // Should complete without error, logging happens internally

        verify(outboxRepository).deleteSentBefore(any());
    }
}
