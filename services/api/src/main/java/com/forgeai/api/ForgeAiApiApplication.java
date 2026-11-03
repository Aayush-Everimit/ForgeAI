package com.forgeai.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.forgeai.api", "com.forgeai.shared"})
@EntityScan(basePackages = "com.forgeai.shared.domain")
@EnableJpaRepositories(basePackages = "com.forgeai.shared.repository")
public class ForgeAiApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeAiApiApplication.class, args);
    }
}
