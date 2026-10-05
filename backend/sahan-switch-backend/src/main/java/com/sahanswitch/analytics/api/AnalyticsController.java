package com.sahanswitch.analytics.api;

import com.sahanswitch.analytics.application.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Task 3: operational dashboard metrics. Administrators only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Operational metrics for monitoring the switch")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Operation(
            summary = "Operational summary of the last N hours (default 24)",
            description = "Volume and value by currency and status, success and failure rate per receiving "
                    + "participant, an hourly trend, and the circuit-breaker state of every active participant."
    )
    @GetMapping("/summary")
    public AnalyticsSummary summary(
            @RequestParam(defaultValue = "" + AnalyticsService.DEFAULT_WINDOW_HOURS) int hours
    ) {
        return analyticsService.summary(hours);
    }
}
