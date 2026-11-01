package com.homework.processor.app;

import com.homework.processor.core.*;
import com.homework.processor.sequential.SequentialProcessor;
import com.homework.processor.parallel.ParallelStripProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * BatchRunner provides a headless way to process images in bulk.
 * It is designed to be called by a UI via a separate worker thread.
 */
public class BatchRunner {

    private static final int DEFAULT_ROWS_PER_STRIP = 64;
    private final AtomicBoolean warmedUp = new AtomicBoolean(false);
    private final Map<BaselineKey, Long> baselineCache = new ConcurrentHashMap<>();
    private final AtomicInteger baselineInvocations = new AtomicInteger(0);

    interface TempDirFactory {
        Path create() throws IOException;
    }

    volatile Path lastBaselineTempDir;
    TempDirFactory tempDirFactory = () -> Files.createTempDirectory("baseline_");

    public enum Mode {
        SEQUENTIAL,
        PARALLEL_IMAGES,
        PARALLEL_STRIPS
    }

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    public static class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    public record RunResult(
        long elapsedNanos,
        int total,
        int processed,
        List<FailedFile> failedFiles,
        List<String> warnings,
        boolean cancelled
    ) {}

    public record FailedFile(String fileName, String reason) {}

    public record Comparison(
        RunResult run,
        Long baselineNanos,
        double speedup,
        double efficiency,
        String baselineWarning
    ) {
        /**
         * Speedup Semantics:
         * - Sequential Mode: 1.0 (baseline is not run)
         * - Cancelled Run: 0.0
         * - Baseline Failed: NaN (baselineWarning contains the reason)
         * - Compare = false (Parallel Mode): NaN (no baseline run, baselineNanos null, baselineWarning null)
         */
    }

    private record BaselineKey(String path, int count, long size) {}

    /**
     * Executes the batch process.
     * elapsedNanos covers the total time the user waits inside run() after validation
     * and file discovery, including pool creation and shutdown.
     */
    public RunResult run(Mode mode, Path inputDir, Path outputDir, int threads, ProgressListener l, CancelToken c) {
        if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
        }

        if (inputDir.equals(outputDir)) {
            throw new IllegalArgumentException("Input and output directories must be different.");
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create output directory: " + outputDir, e);
        }

        List<Path> files = discoverImages(inputDir);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No valid images found in " + inputDir);
        }

        long start = System.nanoTime();
        if (mode == Mode.SEQUENTIAL) {
            return runSequential(files, outputDir, l, c, start);
        } else if (mode == Mode.PARALLEL_IMAGES) {
            return runParallelImages(files, outputDir, threads, l, c, start);
        } else {
            return runParallelStrips(files, outputDir, threads, l, c, start);
        }
    }

    private RunResult runSequential(List<Path> files, Path outputDir, ProgressListener l, CancelToken c, long start) {
        List<FailedFile> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int processed = 0;

        SequentialProcessor processor = new SequentialProcessor();

        for (Path file : files) {
            if (c != null && c.isCancelled()) {
                return new RunResult(System.nanoTime() - start, files.size(), processed, failures, warnings, true);
            }

            try {
                ImageBuffer input = decode(file);
                ImageBuffer output = processor.process(input);
                write(output, file.getFileName().toString(), outputDir);
                processed++;
            } catch (Exception e) {
                failures.add(new FailedFile(file.getFileName().toString(), e.getMessage()));
            }

            if (l != null) {
                l.onProgress(processed + failures.size(), files.size());
            }
        }

        return new RunResult(System.nanoTime() - start, files.size(), processed, failures, warnings, false);
    }

    private RunResult runParallelImages(List<Path> files, Path outputDir, int threads, ProgressListener l, CancelToken c, long start) {
        int total = files.size();
        List<FailedFile> failures = Collections.synchronizedList(new ArrayList<>());
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger doneCount = new AtomicInteger(0);
        Semaphore memSemaphore = new Semaphore(threads);

        AtomicInteger imgThreadId = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("BatchRunner-Images-" + imgThreadId.getAndIncrement());
            return t;
        };
        ExecutorService executor = Executors.newFixedThreadPool(threads, factory);

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    if (c != null && c.isCancelled()) return;

                    try {
                        memSemaphore.acquire();
                        try {
                            if (c != null && c.isCancelled()) return;
                            ImageBuffer input = decode(file);
                            SequentialProcessor processor = new SequentialProcessor();
                            ImageBuffer output = processor.process(input);
                            write(output, file.getFileName().toString(), outputDir);
                            processed.incrementAndGet();
                        } catch (IOException e) {
                            try { Files.deleteIfExists(outputDir.resolve(file.getFileName().toString())); } catch (IOException ignore) {}
                            throw e;
                        } finally {
                            memSemaphore.release();
                        }
                    } catch (Exception e) {
                        failures.add(new FailedFile(file.getFileName().toString(), e.getMessage()));
                    } finally {
                        if (l != null) {
                            l.onProgress(doneCount.incrementAndGet(), total);
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CancellationException | CompletionException e) {
            // Handle join failure on cancel
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    warnings.add("ParallelImage pool shutdown timed out");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return new RunResult(System.nanoTime() - start, total, processed.get(), new ArrayList<>(failures), new ArrayList<>(warnings), c != null && c.isCancelled());
    }

    private RunResult runParallelStrips(List<Path> files, Path outputDir, int threads, ProgressListener l, CancelToken c, long start) {
        int total = files.size();
        List<FailedFile> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int processed = 0;

        AtomicInteger stripThreadId = new AtomicInteger(0);
        ForkJoinPool pool = new ForkJoinPool(threads,
            new ForkJoinPool.ForkJoinWorkerThreadFactory() {
                @Override
                public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
                    ForkJoinWorkerThread w = new ForkJoinWorkerThread(pool) {
                        @Override
                        protected void onStart() {
                            super.onStart();
                        }
                    };
                    w.setName("BatchRunner-Strips-" + stripThreadId.getAndIncrement());
                    return w;
                }
            }, null, false);

        try {
            ParallelStripProcessor psp = new ParallelStripProcessor(threads, DEFAULT_ROWS_PER_STRIP);
            psp.setPool(pool);

            for (Path file : files) {
                if (c != null && c.isCancelled()) {
                    return new RunResult(System.nanoTime() - start, total, processed, failures, warnings, true);
                }

                try {
                    ImageBuffer input = decode(file);
                    ImageBuffer output = psp.process(input);
                    write(output, file.getFileName().toString(), outputDir);
                    processed++;
                } catch (Exception e) {
                    failures.add(new FailedFile(file.getFileName().toString(), e.getMessage()));
                }

                if (l != null) {
                    l.onProgress(processed + failures.size(), total);
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    warnings.add("ParallelStrip pool shutdown timed out");
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return new RunResult(System.nanoTime() - start, total, processed, failures, warnings, false);
    }

    // --- Step 5 Features ---

    public void warmUp(Path inputDir) {
        if (!warmedUp.compareAndSet(false, true)) return;
        try {
            List<Path> files = discoverImages(inputDir);
            int limit = Math.min(2, files.size());
            SequentialProcessor seq = new SequentialProcessor();
            for (int i = 0; i < limit; i++) {
                ImageBuffer buf = decode(files.get(i));
                seq.process(buf);
            }
        } catch (IOException e) {
            // Warmup errors are logged but not thrown
        }
    }

    public void resetWarmUp() {
        warmedUp.set(false);
    }

    public long runSequentialBaseline(Path inputDir, CancelToken c) throws IOException {
        Path tempDir = tempDirFactory.create();
        lastBaselineTempDir = tempDir;
        try {
            List<Path> files = discoverImages(inputDir);

            long totalSize = 0;
            for (Path p : files) totalSize += Files.size(p);
            BaselineKey key = new BaselineKey(inputDir.toRealPath().toString(), files.size(), totalSize);

            if (baselineCache.containsKey(key)) {
                return baselineCache.get(key);
            }

            baselineInvocations.incrementAndGet();
            long start = System.nanoTime();
            SequentialProcessor seq = new SequentialProcessor();
            for (Path file : files) {
                if (c != null && c.isCancelled()) throw new IOException("Cancelled");
                ImageBuffer in = decode(file);
                ImageBuffer out = seq.process(in);
                write(out, file.getFileName().toString(), tempDir);
            }
            long elapsed = System.nanoTime() - start;
            baselineCache.put(key, elapsed);
            return elapsed;
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public Comparison runWithComparison(Mode mode, Path inputDir, Path outputDir, int threads, ProgressListener listener, CancelToken cancel, boolean compare, Consumer<String> status) {
        if (status != null) status.accept("Warming up...");
        warmUp(inputDir);

        Long baselineNanos = null;
        String baselineWarning = null;
        if (compare && mode != Mode.SEQUENTIAL) {
            if (status != null) status.accept("Running sequential baseline...");
            try {
                baselineNanos = runSequentialBaseline(inputDir, cancel);
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) {
                    return new Comparison(
                        new RunResult(0, 0, 0, Collections.emptyList(), Collections.emptyList(), true),
                        null, 0.0, 0.0, null
                    );
                }
                baselineWarning = "baseline failed: " + e.getMessage();
            }
        }

        if (status != null) status.accept("Running " + mode + "...");
        RunResult run = run(mode, inputDir, outputDir, threads, listener, cancel);

        return computeComparison(run, baselineNanos, threads, mode, baselineWarning);
    }

    private Comparison computeComparison(RunResult run, Long baselineNanos, int threads, Mode mode, String baselineWarning) {
        if (run.cancelled()) {
            return new Comparison(run, baselineNanos, 0.0, 0.0, baselineWarning);
        }
        if (mode == Mode.SEQUENTIAL) {
            return new Comparison(run, null, 1.0, 1.0 / 1, baselineWarning);
        }
        if (baselineNanos == null) {
            return new Comparison(run, null, Double.NaN, Double.NaN, baselineWarning);
        }
        double speedup = (double) baselineNanos / run.elapsedNanos();
        double efficiency = speedup / threads;
        return new Comparison(run, baselineNanos, speedup, efficiency, baselineWarning);
    }

    public int getBaselineInvocationCount() {
        return baselineInvocations.get();
    }

    private void deleteDirectory(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (IOException ignore) {}
    }

    private List<Path> discoverImages(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ImageBuffer decode(Path path) throws IOException {
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) {
            throw new IOException("unsupported or corrupt");
        }
        return new ImageBuffer(img);
    }

    public static void write(ImageBuffer buf, String filename, Path outputDir) throws IOException {
        BufferedImage img = new BufferedImage(buf.getWidth(), buf.getHeight(), BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, buf.getWidth(), buf.getHeight(), buf.getPixels(), 0, buf.getWidth());
        ImageIO.write(img, "png", outputDir.resolve(filename).toFile());
    }
}
