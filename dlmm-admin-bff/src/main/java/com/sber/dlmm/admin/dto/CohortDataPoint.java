package com.sber.dlmm.admin.dto;

/**
 * A single data point in a cohort analytics time series.
 *
 * @param date  ISO-8601 date string (yyyy-MM-dd)
 * @param value metric value (DAU count, MAU count, or retention percentage × 100)
 */
public record CohortDataPoint(String date, long value) {}
