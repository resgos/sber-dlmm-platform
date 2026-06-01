package com.sber.dlmm.common.calendar;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Sprint 5 #5.10 — auto-registers {@link BankingCalendarService} as a
 * singleton bean for every dlmm-common consumer. {@code @ConditionalOnMissingBean}
 * lets individual services override with a real-holidays-API-backed
 * implementation later without touching the lib.
 */
@AutoConfiguration
public class DlmmCalendarAutoConfiguration {

    /**
     * Registers the default {@link BankingCalendarService} unless a consumer
     * already defines its own (e.g. a holidays-API-backed implementation).
     *
     * @return the singleton banking-calendar bean
     */
    @Bean
    @ConditionalOnMissingBean
    public BankingCalendarService bankingCalendarService() {
        return new BankingCalendarService();
    }
}
