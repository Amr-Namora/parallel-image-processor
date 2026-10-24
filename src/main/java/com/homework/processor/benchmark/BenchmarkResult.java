package com.homework.processor.benchmark;

/**
 * BenchmarkResult stores raw timing and metadata for a single execution of a processing mode.
 */
public record BenchmarkResult(
    String mode,
    int threadCount,
    int rowsPerStrip,
    long executionTimeNanos,
    String measurementType,
    boolean isCorrect,
    long gcTimeNanos
) {}
