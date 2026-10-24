package com.homework.processor.benchmark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * StatisticsCalculator computes summary metrics from a collection of benchmark results.
 */
public class StatisticsCalculator {

    public record Stats(
        double median,
        double min,
        double max,
        double stdDev
    ) {}

    public static Stats calculate(List<Long> timesNanos) {
        if (timesNanos == null || timesNanos.isEmpty()) {
            return new Stats(0, 0, 0, 0);
        }

        List<Long> sorted = timesNanos.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();

        double median = (n % 2 == 0)
            ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
            : sorted.get(n / 2);

        double min = sorted.get(0);
        double max = sorted.get(n - 1);

        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double variance = sorted.stream()
            .mapToDouble(t -> Math.pow(t - avg, 2))
            .average()
            .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        return new Stats(median, min, max, stdDev);
    }
}
