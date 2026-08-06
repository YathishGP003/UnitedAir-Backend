package com.unitedair.ai;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UnitedAir AI - Smart Flight Booking Assistant.
 *
 * <p>An agentic RAG assistant serving three user classes (Passenger, Airline Staff, Admin)
 * over a governed airline Knowledge Base and a set of registered tools. Every answer is
 * grounded in retrieved KB content or verified tool output; nothing is generated from the
 * model's own recollection.
 *
 * <p>Implements SRS v1.0.
 */
@SpringBootApplication
@EnableConfigurationProperties(UnitedAirProperties.class)
@EnableScheduling
@EnableAsync
public class UnitedAirAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnitedAirAiApplication.class, args);
    }
}
