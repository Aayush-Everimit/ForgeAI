package com.forgeai.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forgeai.worker.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorkerScheduler {

    private final DurableJobWorkerService workerService;

    public JobWorkerScheduler(DurableJobWorkerService workerService) {
        this.workerService = workerService;
    }

    @Scheduled(fixedDelayString = "${forgeai.worker.poll-interval-ms:2000}")
    public void pollJobs() {
        while (!workerService.isShuttingDown() && workerService.claimAndProcessNextJob()) {
            // Drain ready queue up to shutdown signal
        }
    }

    @Scheduled(fixedDelayString = "${forgeai.worker.stale-check-interval-ms:15000}")
    public void recoverStaleLeases() {
        workerService.recoverStaleJobs();
    }
}
