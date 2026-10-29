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
import java.util.stream.Collectors;

/**
 * BatchRunner provides a headless way to process images in bulk.
 * It is designed to be called by a UI via a separate worker thread.
 */
public class BatchRunner {

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
        boolean cancelled
    ) {}

    public record FailedFile(String fileName, String reason) {}

    /**
     * Executes the batch process.
     * For PARALLEL_IMAGES and PARALLEL_STRIPS, elapsedNanos covers the time from the start of
     * the compute phase (pool creation/submission) until all images are processed and
     * the pool is shut down (wall-clock time).
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

        if (mode == Mode.SEQUENTIAL) {
            return runSequential(files, outputDir, l, c);
        } else if (mode == Mode.PARALLEL_IMAGES) {
            return runParallelImages(files, outputDir, threads, l, c);
        } else {
            return runParallelStrips(files, outputDir, threads, l, c);
        }
    }

    private RunResult runSequential(List<Path> files, Path outputDir, ProgressListener l, CancelToken c) {
        List<FailedFile> failures = new ArrayList<>();
        int processed = 0;
        long start = System.nanoTime();

        SequentialProcessor processor = new SequentialProcessor();

        for (Path file : files) {
            if (c != null && c.isCancelled()) {
                return new RunResult(System.nanoTime() - start, files.size(), processed, failures, true);
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

        return new RunResult(System.nanoTime() - start, files.size(), processed, failures, false);
    }

    private RunResult runParallelImages(List<Path> files, Path outputDir, int threads, ProgressListener l, CancelToken c) {
        int total = files.size();
        List<FailedFile> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger doneCount = new AtomicInteger(0);
        Semaphore memSemaphore = new Semaphore(threads);

        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("BatchRunner-ParallelImage-Thread");
            return t;
        };
        ExecutorService executor = Executors.newFixedThreadPool(threads, factory);

        long start = System.nanoTime();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    if (c != null && c.isCancelled()) return;

                    try {
                        memSemaphore.acquire();
                        try {
                            ImageBuffer input = decode(file);
                            SequentialProcessor processor = new SequentialProcessor();
                            ImageBuffer output = processor.process(input);
                            write(output, file.getFileName().toString(), outputDir);
                            processed.incrementAndGet();
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
        } finally {
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

        long end = System.nanoTime();
        return new RunResult(end - start, total, processed.get(), new ArrayList<>(failures), c != null && c.isCancelled());
    }

    private RunResult runParallelStrips(List<Path> files, Path outputDir, int threads, ProgressListener l, CancelToken c) {
        int total = files.size();
        List<FailedFile> failures = new ArrayList<>();
        int processed = 0;

        // Fixed rowsPerStrip = 64 as per harness
        int rowsPerStrip = 64;

        // ForkJoinPool(N) created before timed region
        ForkJoinPool pool = new ForkJoinPool(threads);

        long start = System.nanoTime();
        try {
            ParallelStripProcessor psp = new ParallelStripProcessor(threads, rowsPerStrip);
            psp.setPool(pool);

            for (Path file : files) {
                if (c != null && c.isCancelled()) {
                    return new RunResult(System.nanoTime() - start, total, processed, failures, true);
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
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    // Report error as requested (via failure list or result flag, but RunResult doesn't have a general error field,
                    // so we ensure it's not silent. Since we can't throw from finally, we mark a failure if it happened).
                    failures.add(new FailedFile("System", "ParallelStrip pool shutdown timed out"));
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        long end = System.nanoTime();
        return new RunResult(end - start, total, processed, failures, false);
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
