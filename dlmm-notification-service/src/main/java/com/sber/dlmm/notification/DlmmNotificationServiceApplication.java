package com.sber.dlmm.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.notification", "com.sber.dlmm.common"})
public class DlmmNotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmNotificationServiceApplication.class, args);
    }
}
