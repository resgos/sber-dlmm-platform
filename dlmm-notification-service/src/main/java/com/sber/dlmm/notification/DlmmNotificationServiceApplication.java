package com.sber.dlmm.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the DLMM notification-service (port 8087).
 *
 * <p>This service is a pure Kafka <em>consumer</em>: it subscribes to the platform's
 * domain-event topics ({@code pool-events}, {@code fee-events}, {@code user-events})
 * and turns those raw {@code *-events} into user-facing in-app notifications persisted
 * in the {@code notifications} table. It is the single writer of that table — other
 * services only publish events; the user-facing message wording is crafted here. A
 * small REST API ({@code /api/v1/notifications}) lets the user-ui list notifications,
 * fetch the unread badge count, and mark them read.
 *
 * <p>Operationally it also exposes a custom consumer-lag health indicator so on-call
 * engineers can tell whether notifications are being delivered in time (not just whether
 * the broker is reachable).
 *
 * <p>The component scan is widened to {@code com.sber.dlmm.common} so this service picks
 * up the shared cross-cutting infrastructure (JWT auth filter, global exception handler,
 * etc.) that lives in the {@code dlmm-common} library.
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.notification", "com.sber.dlmm.common"})
public class DlmmNotificationServiceApplication {

    /**
     * Boots the Spring application context and starts the embedded web server plus the
     * Kafka listener containers.
     *
     * @param args standard JVM command-line arguments forwarded to Spring Boot (e.g.
     *             {@code --spring.profiles.active=...} or property overrides)
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmNotificationServiceApplication.class, args);
    }
}
