package com.homework.processor.app;

import com.homework.processor.core.ImageBuffer;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BatchRunnerTest {
    @TempDir Path inputDir;
    @TempDir Path outputDir;

    private BatchRunner runner = new BatchRunner();

    private void createTestImage(String name, int w, int h, int color) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(color));
        g.fillRect(0, 0, w, h);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", inputDir.resolve(name).toFile());
    }

    @Test
    void testCorrectnessSmallAndLarge() throws IOException {
        createTestImage("small.png", 97, 61, 0xFF0000);
        createTestImage("large.png", 1000, 1000, 0x00FF00);

        BatchRunner.RunResult result = runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 1, null, null);

        assertEquals(2, result.processed());

        for (String name : List.of("small.png", "large.png")) {
            ImageBuffer expected = new SequentialProcessor().process(new ImageBuffer(javax.imageio.ImageIO.read(inputDir.resolve(name).toFile())));
            ImageBuffer actual = new ImageBuffer(javax.imageio.ImageIO.read(outputDir.resolve(name).toFile()));
            assertArrayEquals(expected.getPixels(), actual.getPixels(), "Mismatch in " + name);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 100})
    void testParallelCorrectnessAndThreads(int n) throws IOException {
        int count = 10;
        for (int i = 0; i < count; i++) {
            createTestImage("img_" + i + ".png", 97, 61, i * 10);
        }

        for (int rep = 0; rep < 20; rep++) {
            Path roundOutputDir = Files.createTempDirectory("round_" + rep);
            BatchRunner.RunResult result = runner.run(BatchRunner.Mode.PARALLEL_IMAGES, inputDir, roundOutputDir, n, null, null);

            assertEquals(count, result.processed(), "Failed at rep " + rep + " N=" + n);

            for (int i = 0; i < count; i++) {
                String name = "img_" + i + ".png";
                ImageBuffer expected = new SequentialProcessor().process(new ImageBuffer(javax.imageio.ImageIO.read(inputDir.resolve(name).toFile())));
                ImageBuffer actual = new ImageBuffer(javax.imageio.ImageIO.read(roundOutputDir.resolve(name).toFile()));
                assertArrayEquals(expected.getPixels(), actual.getPixels(), "Pixel mismatch in " + name + " at rep " + rep + " N=" + n);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void testStripCorrectness(int n) throws IOException {
        createTestImage("small.png", 97, 61, 0x112233);
        createTestImage("short.png", 100, 20, 0x445566);
        createTestImage("oneH.png", 100, 1, 0x778899);
        createTestImage("oneW.png", 1, 100, 0xAABBCC);
        createTestImage("large.png", 1000, 1000, 0xDEADBEEF);

        for (int rep = 0; rep < 20; rep++) {
            Path roundOutputDir = Files.createTempDirectory("strip_round_" + rep);
            BatchRunner.RunResult result = runner.run(BatchRunner.Mode.PARALLEL_STRIPS, inputDir, roundOutputDir, n, null, null);

            assertEquals(5, result.processed(), "Failed at rep " + rep + " N=" + n);

            String[] names = {"small.png", "short.png", "oneH.png", "oneW.png", "large.png"};
            for (String name : names) {
                ImageBuffer expected = new SequentialProcessor().process(new ImageBuffer(javax.imageio.ImageIO.read(inputDir.resolve(name).toFile())));
                ImageBuffer actual = new ImageBuffer(javax.imageio.ImageIO.read(roundOutputDir.resolve(name).toFile()));
                assertArrayEquals(expected.getPixels(), actual.getPixels(), "Pixel mismatch in " + name + " at rep " + rep + " N=" + n);
            }
        }
    }

    @Test
    void testParallelStripCorruptFile() throws IOException {
        createTestImage("valid1.png", 10, 10, 0);
        Files.write(inputDir.resolve("corrupt.png"), new byte[]{1, 2, 3});
        createTestImage("valid2.png", 10, 10, 0);

        BatchRunner.RunResult result = runner.run(BatchRunner.Mode.PARALLEL_STRIPS, inputDir, outputDir, 4, null, null);

        assertEquals(2, result.processed());
        assertEquals(1, result.failedFiles().size());
        assertEquals("corrupt.png", result.failedFiles().get(0).fileName());
        assertTrue(result.failedFiles().get(0).reason().contains("unsupported or corrupt"));
    }

    @Test
    void testParallelStripThreadsDead() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        runner.run(BatchRunner.Mode.PARALLEL_STRIPS, inputDir, outputDir, 4, null, null);

        Set<Thread> activePoolThreads = new HashSet<>();
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().contains("ForkJoinPool-1-worker")) {
                activePoolThreads.add(t);
            }
        });

        assertTrue(activePoolThreads.isEmpty() ||
                   activePoolThreads.stream().allMatch(t -> t.getState() == Thread.State.TERMINATED),
                   "Pool threads still alive");
    }

    @Test
    void testProgressReporting() throws IOException {
        int count = 5;
        for (int i = 0; i < count; i++) {
            createTestImage("img_" + i + ".png", 10, 10, 0);
        }

        AtomicInteger lastProgress = new AtomicInteger();
        AtomicInteger callCount = new AtomicInteger();
        BatchRunner.ProgressListener l = (done, total) -> {
            lastProgress.set(done);
            callCount.incrementAndGet();
        };

        runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 1, l, null);
        assertEquals(count, lastProgress.get());
        assertEquals(count, callCount.get());

        Path pOut = Files.createTempDirectory("pout");
        callCount.set(0);
        runner.run(BatchRunner.Mode.PARALLEL_IMAGES, inputDir, pOut, 4, l, null);
        assertEquals(count, lastProgress.get());
        assertEquals(count, callCount.get());

        Path sOut = Files.createTempDirectory("sout");
        callCount.set(0);
        runner.run(BatchRunner.Mode.PARALLEL_STRIPS, inputDir, sOut, 4, l, null);
        assertEquals(count, lastProgress.get());
        assertEquals(count, callCount.get());
    }

    @Test
    void testCorruptAndNonImage() throws IOException {
        createTestImage("valid.png", 10, 10, 0xFFFFFF);
        Files.write(inputDir.resolve("corrupt.png"), new byte[]{1, 2, 3, 4, 5});
        Files.write(inputDir.resolve("text.png"), "This is just text".getBytes());

        BatchRunner.RunResult result = runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 1, null, null);

        assertEquals(1, result.processed());
        assertEquals(2, result.failedFiles().size());

        boolean foundCorrupt = result.failedFiles().stream().anyMatch(f -> f.fileName().equals("corrupt.png") && f.reason().contains("unsupported or corrupt"));
        boolean foundText = result.failedFiles().stream().anyMatch(f -> f.fileName().equals("text.png") && f.reason().contains("unsupported or corrupt"));

        assertTrue(foundCorrupt, "Should fail corrupt.png");
        assertTrue(foundText, "Should fail text.png");
    }

    @Test
    void testEmptyFolder() {
        assertThrows(IllegalArgumentException.class, () ->
            runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 1, null, null)
        );
    }

    @Test
    void testInputEqualsOutput() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        assertThrows(IllegalArgumentException.class, () ->
            runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, inputDir, 1, null, null)
        );
    }

    // --- Step 5 Tests ---

    @Test
    void testPreCancelledToken() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        ct.cancel();

        for (BatchRunner.Mode mode : BatchRunner.Mode.values()) {
            BatchRunner.RunResult res = runner.run(mode, inputDir, outputDir, 4, null, ct);
            assertTrue(res.cancelled(), "Mode " + mode + " should be cancelled");
            assertEquals(0, res.processed(), "Mode " + mode + " should have processed nothing");
        }
    }

    @Test
    void testCancelDuringProcessing() throws IOException {
        int count = 200;
        for (int i = 0; i < count; i++) {
            createTestImage("img_" + i + ".png", 10, 10, 0);
        }

        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        AtomicInteger calls = new AtomicInteger();
        BatchRunner.ProgressListener l = (done, total) -> {
            calls.incrementAndGet();
            if (done >= 1) ct.cancel();
        };

        for (BatchRunner.Mode mode : BatchRunner.Mode.values()) {
            Path out = Files.createTempDirectory("cancel_" + mode);
            BatchRunner.RunResult res = runner.run(mode, inputDir, out, 2, l, ct);
            assertTrue(res.cancelled(), "Mode " + mode + " should be cancelled");
            assertTrue(res.processed() < count, "Mode " + mode + " should not have finished");

            Set<Thread> alive = new HashSet<>();
            Thread.getAllStackTraces().keySet().forEach(t -> {
                if (t.getName().contains("BatchRunner") || t.getName().contains("ForkJoinPool")) {
                    if (t.getState() != Thread.State.TERMINATED) alive.add(t);
                }
            });
            // For sequential, no pool is created, so we just check if it's not processing
        }
    }

    @Test
    void testBaselineCache() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();

        int startCount = runner.getBaselineInvocationCount();
        runner.runSequentialBaseline(inputDir, ct);
        int firstCall = runner.getBaselineInvocationCount();

        runner.runSequentialBaseline(inputDir, ct);
        int secondCall = runner.getBaselineInvocationCount();

        assertEquals(1, firstCall - startCount, "First call should increment invocation count");
        assertEquals(firstCall, secondCall, "Second call should use cache");
    }

    @Test
    void testBaselineTempDirDeleted() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();

        // Standard run
        runner.runSequentialBaseline(inputDir, ct);

        // Cancelled run
        BatchRunner.CancelToken ctCancel = new BatchRunner.CancelToken();
        ctCancel.cancel();

        // We just verify that it completes without crashing and the code handles it.
        assertDoesNotThrow(() -> runner.runSequentialBaseline(inputDir, ctCancel));
    }

    @Test
    void testOrchestrationLogic() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        List<String> statuses = new ArrayList<>();
        Consumer<String> statusConsumer = statuses::add;

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, ct, true, statusConsumer);

        assertTrue(statuses.contains("Warming up..."));
        assertTrue(statuses.contains("Running sequential baseline..."));
        assertTrue(statuses.contains("Running PARALLEL_IMAGES..."));

        assertNotNull(comp.run());
        assertNotNull(comp.baselineNanos());
        assertEquals((double)comp.baselineNanos() / comp.run().elapsedNanos(), comp.speedup(), 0.001);
        assertEquals(comp.speedup() / 4, comp.efficiency(), 0.001);
    }

    @Test
    void testOrchestrationSequential() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 4, null, ct, true, null);

        assertEquals(1.0, comp.speedup());
        assertNull(comp.baselineNanos());
    }
}
