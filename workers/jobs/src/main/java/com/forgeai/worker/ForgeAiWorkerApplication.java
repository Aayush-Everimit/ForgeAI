package com.forgeai.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.forgeai.worker", "com.forgeai.shared"})
@EntityScan(basePackages = "com.forgeai.shared.domain")
@EnableJpaRepositories(basePackages = "com.forgeai.shared.repository")
@EnableScheduling
public class ForgeAiWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeAiWorkerApplication.class, args);
    }
}
