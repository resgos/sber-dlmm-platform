package com.sber.dlmm.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common"})
public class DlmmTransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmTransactionServiceApplication.class, args);
    }
}
