package com.sber.dlmm.fee;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Task #11 — common.exception scan so GlobalExceptionHandler picks up
// thrown DlmmException subclasses with their declared httpStatus
// instead of falling through to Spring Security 403. See pool-engine
// fix for the full story.
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.fee", "com.sber.dlmm.common.exception"})
public class DlmmFeeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmFeeServiceApplication.class, args);
    }
}
