package com.forgeai.worker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WorkerHealthController {

    private final DurableJobWorkerService workerService;

    public WorkerHealthController(DurableJobWorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", workerService.isShuttingDown() ? "shutting_down" : "ok",
                "service", "worker",
                "runtime", "java-spring-boot"
        );
    }
}
