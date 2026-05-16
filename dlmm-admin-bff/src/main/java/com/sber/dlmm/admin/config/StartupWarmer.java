package com.sber.dlmm.admin.config;

import com.sber.dlmm.admin.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Calls /admin/dashboard once on startup so the first real admin-page
 * load doesn't pay the WebClient cold-start tax (Netty connection
 * pool setup, Hikari fill across all 3 downstream calls, etc.).
 *
 * Empirically (Sprint 1 risk register #14) the cold-path was 2.9s and
 * the warm path 150ms. We swallow exceptions: if one downstream is
 * slow to come up, the warm-up tick fails but the BFF keeps starting
 * — the first real request will just pay the cost itself.
 */
@Component
public class StartupWarmer {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmer.class);

    private final AdminService adminService;

    public StartupWarmer(AdminService adminService) {
        this.adminService = adminService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        long started = System.currentTimeMillis();
        try {
            adminService.getDashboard();
            log.info("Dashboard warm complete in {} ms", System.currentTimeMillis() - started);
        } catch (Exception ex) {
            log.warn("Dashboard warm failed (non-fatal): {}", ex.toString());
        }
    }
}
