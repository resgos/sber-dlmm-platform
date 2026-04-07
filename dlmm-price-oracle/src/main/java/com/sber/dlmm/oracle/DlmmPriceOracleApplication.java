package com.sber.dlmm.oracle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.oracle", "com.sber.dlmm.common"})
@EnableScheduling
public class DlmmPriceOracleApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmPriceOracleApplication.class, args);
    }
}
