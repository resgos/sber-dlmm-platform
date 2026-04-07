package com.sber.dlmm.pool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DlmmPoolEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmPoolEngineApplication.class, args);
    }
}
