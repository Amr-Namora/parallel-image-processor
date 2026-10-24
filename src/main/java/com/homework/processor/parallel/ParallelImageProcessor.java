package com.homework.processor.parallel;

import com.homework.processor.core.*;
import com.homework.processor.sequential.SequentialProcessor;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * ParallelImageProcessor distributes different images across a thread pool.
 *
 * Engineering Justifications:
 * 1. Semaphore: Bounds the number of images in flight to prevent OutOfMemoryError.
 * 2. Result Array: Each task writes to its own pre-allocated slot, avoiding locks.
 * 3. Compute vs I/O: Supports separate modes to allow Amdahl's Law analysis.
 */
public class ParallelImageProcessor {

    public record BatchResult(
        ImageBuffer[] results,
        List<String> failedImages,
        int processedCount,
        long totalComputeTimeNanos
    ) {}

    private final int numThreads;
    private final int maxInFlightImages;
    private final Semaphore memorySemaphore;

    public ParallelImageProcessor(int numThreads, int maxInFlightImages) {
        this.numThreads = numThreads;
        this.maxInFlightImages = maxInFlightImages;
        this.memorySemaphore = new Semaphore(maxInFlightImages);
    }

    /**
     * FULL MODE: Includes I/O (Decode -> Process -> Results).
     * Recommended -Xmx: For 4K images (approx 33MB per array), we have ~4 arrays per image.
     * Total memory per image approx 132MB.
     * Recommended -Xmx = (maxInFlightImages * 150MB) + 512MB overhead.
     * Default 4 permits -> ~1GB.
     */
    public BatchResult processBatch(List<File> files) {
        int n = files.size();
        ImageBuffer[] results = new ImageBuffer[n];
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger progress = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        long startCompute = System.nanoTime();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                final File file = files.get(i);

                futures.add(executor.submit(() -> {
                    try {
                        memorySemaphore.acquire();
                        try {
                            // I/O: Decode
                            BufferedImage img = ImageIO.read(file);
                            if (img == null) throw new Exception("Invalid image file");

                            ImageBuffer input = new ImageBuffer(img);

                            // Compute: Use sequential pipeline for a single image
                            SequentialProcessor seq = new SequentialProcessor();
                            results[index] = seq.process(input);

                        } finally {
                            memorySemaphore.release();
                        }
                        progress.incrementAndGet();
                    } catch (Exception e) {
                        failures.add(file.getName() + ": " + e.getMessage());
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    // Task internals already handle exceptions into 'failures' list
                }
            }
        } finally {
            shutdownExecutor(executor);
        }

        long endCompute = System.nanoTime();
        return new BatchResult(results, failures, progress.get(), (endCompute - startCompute));
    }

    /**
     * COMPUTE-ONLY MODE: Process pre-loaded buffers.
     * Used for pure algorithm benchmarking.
     */
    public BatchResult processBuffers(List<ImageBuffer> inputs) {
        int n = inputs.size();
        ImageBuffer[] results = new ImageBuffer[n];
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger progress = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        long startCompute = System.nanoTime();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                final ImageBuffer input = inputs.get(i);

                futures.add(executor.submit(() -> {
                    try {
                        SequentialProcessor seq = new SequentialProcessor();
                        results[index] = seq.process(input);
                        progress.incrementAndGet();
                    } catch (Exception e) {
                        failures.add("Buffer_" + index + ": " + e.getMessage());
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                }
            }
        } finally {
            shutdownExecutor(executor);
        }

        long endCompute = System.nanoTime();
        return new BatchResult(results, failures, progress.get(), (endCompute - startCompute));
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
