package com.homework.processor.benchmark;

import com.homework.processor.core.*;
import com.homework.processor.sequential.SequentialProcessor;
import com.homework.processor.parallel.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import javax.imageio.ImageIO;

/**
 * BenchmarkHarness orchestrates the measurement of the image processing pipeline.
 */
public class BenchmarkHarness {

    private static final String GOLDEN_FILE = "golden_checksums.txt";
    private static final String TEMP_OUTPUT_DIR = "benchmark_temp_out";

    public enum Mode {
        SEQUENTIAL("Sequential"),
        PARALLEL_IMAGE("ParallelImage"),
        PARALLEL_STRIP("ParallelStrip");

        public final String label;
        Mode(String label) { this.label = label; }
    }

    public enum MeasurementType {
        COMPUTE_ONLY, FULL_PHASED, FULL_PIPELINED
    }

    public record PhaseTiming(
        long decodeNanos,
        long computeNanos,
        long writeNanos,
        long wallClockNanos,
        long sumDecodeNanos,
        long sumComputeNanos,
        long sumWriteNanos,
        int maxConcurrentDecodes,
        int maxConcurrentWrites
    ) {}

    public record BenchmarkResult(
        String mode,
        int threadCount,
        int rowsPerStrip,
        long executionTimeNanos,
        String measurementType,
        boolean isCorrect,
        long gcTimeMs,
        int rep,
        int round,
        PhaseTiming phaseTiming
    ) {}

    public static void main(String[] args) {
        String datasetPath = getArg(args, "--dataset", "./dataset");
        int repetitions = Integer.parseInt(getArg(args, "--reps", "5"));
        int rowsPerStrip = Integer.parseInt(getArg(args, "--strips", "64"));
        String outputCsvBase = getArg(args, "--out", "results");
        MeasurementType type = MeasurementType.valueOf(getArg(args, "--mode", "COMPUTE_ONLY").toUpperCase());
        int rounds = Integer.parseInt(getArg(args, "--rounds", "1"));

        int readerThreads = Integer.parseInt(getArg(args, "--readers", "2"));
        int writerThreads = Integer.parseInt(getArg(args, "--writers", "4"));
        int semaphorePermits = Integer.parseInt(getArg(args, "--permits", "4"));

        String profile = getArg(args, "--profile", "default");
        if ("quick".equals(profile)) {
            repetitions = 1;
            rounds = 1;
        } else if ("medium".equals(profile)) {
            repetitions = 3;
            rounds = 1;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String outputCsv = outputCsvBase + "_" + timestamp + ".csv";

        System.out.println("Starting BenchmarkHarness...");
        System.out.println("Dataset: " + datasetPath + " | Reps: " + repetitions + " | RowsPerStrip: " + rowsPerStrip);
        System.out.println("Measurement Type: " + type + " | Rounds: " + rounds + " | Profile: " + profile);

        try {
            List<File> files = Files.list(Paths.get(datasetPath))
                    .filter(p -> p.toString().endsWith(".png"))
                    .map(Path::toFile)
                    .sorted(Comparator.comparing(File::getName))
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                System.err.println("No PNG files found in " + datasetPath);
                System.exit(1);
            }

            System.out.println("Generating golden checksums...");
            Map<String, Long> goldenChecksums = runSequentialGolden(files);

            System.out.println("Warming up JVM...");
            performWarmup(files, rowsPerStrip);

            List<BenchmarkResult> allResults = new ArrayList<>();
            int[] threadCounts = {1, 2, 4, 8, 16};

            for (int round = 0; round < rounds; round++) {
                System.out.println("Running Round " + (round + 1) + "...");
                for (int n : threadCounts) {
                    System.out.println("  Measuring N = " + n + "...");
                    allResults.addAll(measureMode(Mode.SEQUENTIAL, 1, rowsPerStrip, files, type, goldenChecksums, repetitions, round, readerThreads, writerThreads, semaphorePermits));
                    allResults.addAll(measureMode(Mode.PARALLEL_IMAGE, n, rowsPerStrip, files, type, goldenChecksums, repetitions, round, readerThreads, writerThreads, semaphorePermits));
                    allResults.addAll(measureMode(Mode.PARALLEL_STRIP, n, rowsPerStrip, files, type, goldenChecksums, repetitions, round, readerThreads, writerThreads, semaphorePermits));
                }
            }

            exportCsv(allResults, outputCsv, files.size(), type);
            System.out.println("Benchmarks complete. Results saved to " + outputCsv);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Map<String, Long> runSequentialGolden(List<File> files) throws IOException {
        SequentialProcessor seq = new SequentialProcessor();
        Map<String, Long> checksums = new LinkedHashMap<>();
        for (File f : files) {
            BufferedImage img = decodeImage(f);
            ImageBuffer input = new ImageBuffer(img);
            ImageBuffer output = seq.process(input);
            checksums.put(f.getName(), output.calculateChecksum());
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(GOLDEN_FILE))) {
            checksums.forEach((name, sum) -> pw.println(name + "," + sum));
        }
        return checksums;
    }

    private static void performWarmup(List<File> files, int rowsPerStrip) {
        List<File> subset = files.subList(0, Math.min(5, files.size()));
        SequentialProcessor seq = new SequentialProcessor();
        ParallelImageProcessor pip = new ParallelImageProcessor(4, 4);
        ParallelStripProcessor psp = new ParallelStripProcessor(4, rowsPerStrip);

        for (int i = 0; i < 5; i++) {
            for (File f : subset) {
                try {
                    BufferedImage img = decodeImage(f);
                    ImageBuffer buf = new ImageBuffer(img);
                    seq.process(buf);
                    pip.processBuffers(Collections.singletonList(buf));
                    psp.processFixed(buf);
                } catch (IOException e) {
                    System.err.println("Warmup error: " + e.getMessage());
                }
            }
        }
    }

    private static List<BenchmarkResult> measureMode(Mode mode, int n, int rowsPerStrip, List<File> files, MeasurementType type, Map<String, Long> golden, int reps, int round, int readers, int writers, int permits) {
        List<BenchmarkResult> results = new ArrayList<>();
        List<Integer> repIndices = IntStream.range(0, reps).boxed().collect(Collectors.toList());
        Collections.shuffle(repIndices, new Random(42));

        for (int rep : repIndices) {
            long gcStart = getGcTime();
            boolean correct = true;

            try {
                if (type == MeasurementType.COMPUTE_ONLY) {
                    List<ImageBuffer> inputs = new ArrayList<>();
                    for (File f : files) {
                        try { inputs.add(new ImageBuffer(decodeImage(f))); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    }

                    long tStart = System.nanoTime();
                    ImageBuffer[] outputs = executeMode(mode, n, rowsPerStrip, inputs);
                    long tEnd = System.nanoTime();

                    correct = verify(files, outputs, golden);
                    results.add(new BenchmarkResult(mode.label, n, rowsPerStrip, tEnd - tStart, "COMPUTE_ONLY", correct, getGcTime() - gcStart, rep, round, new PhaseTiming(0, tEnd - tStart, 0, tEnd - tStart, 0, tEnd - tStart, 0, 0, 0)));
                } else if (type == MeasurementType.FULL_PHASED) {
                    long tStart = System.nanoTime();
                    PhaseTiming phase = executeFullModeWithTiming(mode, n, rowsPerStrip, files, false);
                    long tEnd = System.nanoTime();

                    ImageBuffer[] outputs = executeFullMode(mode, n, rowsPerStrip, files);
                    correct = verify(files, outputs, golden);
                    cleanupTempDir();
                    results.add(new BenchmarkResult(mode.label, n, rowsPerStrip, tEnd - tStart, "FULL_PHASED", correct, getGcTime() - gcStart, rep, round, phase));
                } else if (type == MeasurementType.FULL_PIPELINED) {
                    long tStart = System.nanoTime();
                    PhaseTiming phase = executePipelinedMode(mode, n, rowsPerStrip, files, golden, readers, writers, permits);
                    long tEnd = System.nanoTime();

                    correct = verifyDiskSample(files, golden);
                    cleanupTempDir();
                    results.add(new BenchmarkResult(mode.label, n, rowsPerStrip, tEnd - tStart, "FULL_PIPELINED", correct, getGcTime() - gcStart, rep, round, phase));
                }
            } catch (Exception e) {
                System.err.println("Error in " + mode + " N=" + n + " Rep=" + rep + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        return results;
    }

    private static ImageBuffer[] executeMode(Mode mode, int n, int rowsPerStrip, List<ImageBuffer> inputs) {
        switch (mode) {
            case SEQUENTIAL:
                SequentialProcessor seq = new SequentialProcessor();
                return inputs.stream().map(seq::process).toArray(ImageBuffer[]::new);
            case PARALLEL_IMAGE:
                ParallelImageProcessor pip = new ParallelImageProcessor(n, 4);
                return pip.processBuffers(inputs).results();
            case PARALLEL_STRIP:
                ParallelStripProcessor psp = new ParallelStripProcessor(n, rowsPerStrip);
                return inputs.stream().map(psp::processFixed).toArray(ImageBuffer[]::new);
            default: throw new IllegalArgumentException();
        }
    }

    private static PhaseTiming executeFullModeWithTiming(Mode mode, int n, int rowsPerStrip, List<File> files, boolean pipelined) throws IOException {
        long decodeTotal = 0, computeTotal = 0, writeTotal = 0;
        long wallStart = System.nanoTime();

        File outDir = new File(TEMP_OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        if (mode == Mode.SEQUENTIAL) {
            SequentialProcessor seq = new SequentialProcessor();
            for (File f : files) {
                long s1 = System.nanoTime();
                BufferedImage img = decodeImage(f);
                ImageBuffer in = new ImageBuffer(img);
                decodeTotal += (System.nanoTime() - s1);

                long s2 = System.nanoTime();
                ImageBuffer out = seq.process(in);
                computeTotal += (System.nanoTime() - s2);

                long s3 = System.nanoTime();
                writeImage(out, f.getName());
                writeTotal += (System.nanoTime() - s3);
            }
        } else if (mode == Mode.PARALLEL_IMAGE) {
            long s1 = System.nanoTime();
            List<ImageBuffer> inputs = new ArrayList<>();
            for (File f : files) {
                inputs.add(new ImageBuffer(decodeImage(f)));
            }
            decodeTotal = System.nanoTime() - s1;

            long s2 = System.nanoTime();
            ParallelImageProcessor pip = new ParallelImageProcessor(n, 4);
            ImageBuffer[] outputs = pip.processBuffers(inputs).results();
            computeTotal = System.nanoTime() - s2;

            long s3 = System.nanoTime();
            for (int i=0; i<outputs.length; i++) {
                if (outputs[i] != null) writeImage(outputs[i], "pip_" + i + ".png");
            }
            writeTotal = System.nanoTime() - s3;
        } else {
            long s1 = System.nanoTime();
            List<ImageBuffer> inputs = new ArrayList<>();
            for (File f : files) {
                inputs.add(new ImageBuffer(decodeImage(f)));
            }
            decodeTotal = System.nanoTime() - s1;

            long s2 = System.nanoTime();
            ParallelStripProcessor psp = new ParallelStripProcessor(n, rowsPerStrip);
            List<ImageBuffer> outputs = new ArrayList<>();
            for (ImageBuffer in : inputs) {
                outputs.add(psp.processFixed(in));
            }
            computeTotal = System.nanoTime() - s2;

            long s3 = System.nanoTime();
            for (int i=0; i<outputs.size(); i++) {
                if (outputs.get(i) != null) writeImage(outputs.get(i), "psp_" + i + ".png");
            }
            writeTotal = System.nanoTime() - s3;
        }

        long wallEnd = System.nanoTime();
        return new PhaseTiming(decodeTotal, computeTotal, writeTotal, wallEnd - wallStart, decodeTotal, computeTotal, writeTotal, 1, 1);
    }

    private static PhaseTiming executePipelinedMode(Mode mode, int n, int rowsPerStrip, List<File> files, Map<String, Long> golden, int readers, int writers, int permits) throws IOException {
        AtomicLong sumDecode = new AtomicLong(0), sumCompute = new AtomicLong(0), sumWrite = new AtomicLong(0);
        AtomicInteger concurrentDecodes = new AtomicInteger(0), concurrentWrites = new AtomicInteger(0);
        AtomicInteger maxDecodes = new AtomicInteger(0), maxWrites = new AtomicInteger(0);
        Semaphore memSemaphore = new Semaphore(permits);

        long wallStart = System.nanoTime();
        File outDir = new File(TEMP_OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        if (mode == Mode.PARALLEL_IMAGE) {
            ExecutorService executor = Executors.newFixedThreadPool(n);
            // Use a single processor to avoid pool recreation overhead
            ParallelImageProcessor sharedPip = new ParallelImageProcessor(1, 4);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (File f : files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        memSemaphore.acquire();
                        long s1 = System.nanoTime();
                        concurrentDecodes.incrementAndGet();
                        maxDecodes.accumulateAndGet(concurrentDecodes.get(), Math::max);
                        BufferedImage img = decodeImage(f);
                        ImageBuffer in = new ImageBuffer(img);
                        sumDecode.addAndGet(System.nanoTime() - s1);
                        concurrentDecodes.decrementAndGet();

                        long s2 = System.nanoTime();
                        // Sequential process on the shared processor (it is stateless)
                        ImageBuffer out = sharedPip.processBuffers(Collections.singletonList(in)).results()[0];
                        sumCompute.addAndGet(System.nanoTime() - s2);

                        long s3 = System.nanoTime();
                        concurrentWrites.incrementAndGet();
                        maxWrites.accumulateAndGet(concurrentWrites.get(), Math::max);
                        if (out.calculateChecksum() != golden.get(f.getName())) {
                            System.err.println("Checksum mismatch: " + f.getName());
                        }
                        writeImage(out, f.getName());
                        sumWrite.addAndGet(System.nanoTime() - s3);
                        concurrentWrites.decrementAndGet();
                    } catch (Exception e) { e.printStackTrace(); }
                    finally { memSemaphore.release(); }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();
        } else if (mode == Mode.PARALLEL_STRIP) {
            BlockingQueue<PipelineTask> decodeQueue = new LinkedBlockingQueue<>(permits);
            BlockingQueue<PipelineTask> computeQueue = new LinkedBlockingQueue<>(permits);
            CountDownLatch latch = new CountDownLatch(files.size());

            // Reuse a single processor instance
            ParallelStripProcessor sharedPsp = new ParallelStripProcessor(n, rowsPerStrip);

            ExecutorService readerPool = Executors.newFixedThreadPool(readers);
            for (File f : files) {
                readerPool.submit(() -> {
                    try {
                        memSemaphore.acquire();
                        long s1 = System.nanoTime();
                        concurrentDecodes.incrementAndGet();
                        maxDecodes.accumulateAndGet(concurrentDecodes.get(), Math::max);
                        BufferedImage img = decodeImage(f);
                        ImageBuffer in = new ImageBuffer(img);
                        sumDecode.addAndGet(System.nanoTime() - s1);
                        concurrentDecodes.decrementAndGet();
                        decodeQueue.put(new PipelineTask(f, in));
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }

            ExecutorService computePool = Executors.newFixedThreadPool(n);
            for (int i = 0; i < n; i++) {
                computePool.submit(() -> {
                    try {
                        while (true) {
                            PipelineTask task = decodeQueue.poll(1, TimeUnit.SECONDS);
                            if (task == null) {
                                if (latch.getCount() == 0) break;
                                continue;
                            }
                            long s2 = System.nanoTime();
                            // Process using the shared processor's parallel implementation
                            task.output = sharedPsp.processFixed(task.input);
                            sumCompute.addAndGet(System.nanoTime() - s2);
                            computeQueue.put(task);
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }

            ExecutorService writerPool = Executors.newFixedThreadPool(writers);
            for (int i = 0; i < writers; i++) {
                writerPool.submit(() -> {
                    try {
                        while (true) {
                            PipelineTask task = computeQueue.poll(1, TimeUnit.SECONDS);
                            if (task == null) {
                                if (latch.getCount() == 0) break;
                                continue;
                            }
                            long s3 = System.nanoTime();
                            concurrentWrites.incrementAndGet();
                            maxWrites.accumulateAndGet(concurrentWrites.get(), Math::max);
                            if (task.output.calculateChecksum() != golden.get(task.file.getName())) {
                                System.err.println("Checksum mismatch: " + task.file.getName());
                            }
                            writeImage(task.output, task.file.getName());
                            sumWrite.addAndGet(System.nanoTime() - s3);
                            concurrentWrites.decrementAndGet();
                            memSemaphore.release();
                            latch.countDown();
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }
            try { latch.await(); } catch (InterruptedException e) { e.printStackTrace(); }
            readerPool.shutdownNow();
            computePool.shutdownNow();
            writerPool.shutdownNow();
        } else {
            throw new UnsupportedOperationException("Sequential not pipelined");
        }

        long wallEnd = System.nanoTime();
        return new PhaseTiming(0, 0, 0, wallEnd - wallStart, sumDecode.get(), sumCompute.get(), sumWrite.get(), maxDecodes.get(), maxWrites.get());
    }

    private static ImageBuffer[] executeFullMode(Mode mode, int n, int rowsPerStrip, List<File> files) throws IOException {
        ImageBuffer[] results;
        if (mode == Mode.SEQUENTIAL) {
            SequentialProcessor seq = new SequentialProcessor();
            results = new ImageBuffer[files.size()];
            for (int i = 0; i < files.size(); i++) {
                results[i] = seq.process(new ImageBuffer(decodeImage(files.get(i))));
            }
        } else if (mode == Mode.PARALLEL_IMAGE) {
            ParallelImageProcessor pip = new ParallelImageProcessor(n, 4);
            results = pip.processBatch(files).results();
        } else {
            ParallelStripProcessor psp = new ParallelStripProcessor(n, rowsPerStrip);
            results = new ImageBuffer[files.size()];
            for (int i = 0; i < files.size(); i++) {
                results[i] = psp.processFixed(new ImageBuffer(decodeImage(files.get(i))));
            }
        }

        File outDir = new File(TEMP_OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();
        for (int i = 0; i < results.length; i++) {
            if (results[i] != null) writeImage(results[i], "res_" + i + ".png");
        }
        return results;
    }

    private static BufferedImage decodeImage(File f) throws IOException {
        return ImageIO.read(f);
    }

    private static void writeImage(ImageBuffer buf, String filename) throws IOException {
        BufferedImage img = new BufferedImage(buf.getWidth(), buf.getHeight(), BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, buf.getWidth(), buf.getHeight(), buf.getPixels(), 0, buf.getWidth());
        ImageIO.write(img, "png", new File(TEMP_OUTPUT_DIR, filename));
    }

    private static boolean verify(List<File> files, ImageBuffer[] outputs, Map<String, Long> golden) {
        for (int i = 0; i < files.size(); i++) {
            if (outputs[i] == null) return false;
            long actual = outputs[i].calculateChecksum();
            long expected = golden.get(files.get(i).getName());
            if (actual != expected) return false;
        }
        return true;
    }

    private static boolean verifyDiskSample(List<File> files, Map<String, Long> golden) {
        Random rnd = new Random();
        for (int i = 0; i < Math.min(5, files.size()); i++) {
            File f = files.get(rnd.nextInt(files.size()));
            try {
                BufferedImage img = decodeImage(f);
                ImageBuffer buf = new ImageBuffer(img);
                if (buf.calculateChecksum() != golden.get(f.getName())) return false;
            } catch (IOException e) { return false; }
        }
        return true;
    }

    private static void cleanupTempDir() {
        File dir = new File(TEMP_OUTPUT_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir.delete();
        }
    }

    private static long getGcTime() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += bean.getCollectionTime();
        }
        return total;
    }

    private static void exportCsv(List<BenchmarkResult> results, String fileName, int imageCount, MeasurementType type) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(System.getenv("PROCESSOR_IDENTIFIER")).append("\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Xmx: ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append("MB\n");
        sb.append("Images: ").append(imageCount).append("\n");
        sb.append("Measurement Type: ").append(type).append("\n");
        sb.append("Mode,ThreadCount,RowsPerStrip,Rep,Round,Time_ms,GC_ms,Correct,Wall_Decode_ms,Wall_Compute_ms,Wall_Write_ms,Sum_Decode_ms,Sum_Compute_ms,Sum_Write_ms,Max_Dec,Max_Write\n");

        for (BenchmarkResult r : results) {
            sb.append(String.format("%s,%d,%d,%d,%d,%.3f,%.3f,%b,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d\n",
                    r.mode(), r.threadCount(), r.rowsPerStrip(),
                    r.rep(), r.round(),
                    r.executionTimeNanos() / 1_000_000.0,
                    (double)r.gcTimeMs(),
                    r.isCorrect(),
                    r.phaseTiming().decodeNanos() / 1_000_000.0,
                    r.phaseTiming().computeNanos() / 1_000_000.0,
                    r.phaseTiming().writeNanos() / 1_000_000.0,
                    r.phaseTiming().sumDecodeNanos() / 1_000_000.0,
                    r.phaseTiming().sumComputeNanos() / 1_000_000.0,
                    r.phaseTiming().sumWriteNanos() / 1_000_000.0,
                    r.phaseTiming().maxConcurrentDecodes(),
                    r.phaseTiming().maxConcurrentWrites()));
        }

        Files.write(Paths.get(fileName), sb.toString().getBytes());
    }

    private static String getArg(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultValue;
    }

    private static class PipelineTask {
        File file;
        ImageBuffer input;
        ImageBuffer output;
        PipelineTask(File f, ImageBuffer in) { this.file = f; this.input = in; }
    }
}
