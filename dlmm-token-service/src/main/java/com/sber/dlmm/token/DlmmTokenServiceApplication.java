package com.sber.dlmm.token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling lights up the OutboxDispatcher tick. Required for
// the transactional-outbox pattern to flush events to Kafka.
@SpringBootApplication
@EnableScheduling
public class DlmmTokenServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmTokenServiceApplication.class, args);
    }
}
