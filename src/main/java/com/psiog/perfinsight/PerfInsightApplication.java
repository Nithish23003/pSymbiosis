package com.psiog.perfinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class PerfInsightApplication {
    public static void main(String[] args) {
        SpringApplication.run(PerfInsightApplication.class, args);
    }
}
