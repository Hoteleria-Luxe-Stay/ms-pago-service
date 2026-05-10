package com.hotel.pago.infrastructure.jobs;

import com.hotel.pago.core.outbox.service.OutboxRelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayJobTest {

    @Mock
    private OutboxRelayService outboxRelayService;

    @InjectMocks
    private OutboxRelayJob relayJob;

    @Test
    void runDelegatesToRelayService() {
        when(outboxRelayService.relayBatch()).thenReturn(5);

        relayJob.run();

        verify(outboxRelayService).relayBatch();
    }

    @Test
    void runCatchesRuntimeExceptionWithoutPropagating() {
        when(outboxRelayService.relayBatch()).thenThrow(new RuntimeException("DB down"));

        // Should not throw — the job swallows exceptions to keep the scheduler alive
        relayJob.run();

        verify(outboxRelayService).relayBatch();
    }
}
