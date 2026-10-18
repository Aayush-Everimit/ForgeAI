package com.forgeai.api.health;

import com.forgeai.shared.rag.RagServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final RagServiceClient ragServiceClient;

    public HealthController(RagServiceClient ragServiceClient) {
        this.ragServiceClient = ragServiceClient;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "api",
                "runtime", "java-spring-boot",
                "ragReady", ragServiceClient.isReady()
        );
    }
}
