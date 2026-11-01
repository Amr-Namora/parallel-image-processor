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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BatchRunnerTest {
    @TempDir Path inputDir;
    @TempDir Path outputDir;

    private BatchRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BatchRunner();
    }

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

        Set<Thread> alive = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("BatchRunner-Images-") || t.getName().startsWith("BatchRunner-S trips-"))
            .filter(Thread::isAlive)
            .collect(Collectors.toSet());

        assertTrue(alive.isEmpty(), "Pool threads still alive: " + alive);
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

        for (BatchRunner.Mode mode : BatchRunner.Mode.values()) {
            Path out = Files.createTempDirectory("cancel_" + mode);
            BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
            AtomicInteger calls = new AtomicInteger();
            BatchRunner.ProgressListener l = (done, total) -> {
                calls.incrementAndGet();
                if (done >= 1) ct.cancel();
            };
            BatchRunner.RunResult res = runner.run(mode, inputDir, out, 2, l, ct);
            assertTrue(res.cancelled(), "Mode " + mode + " should be cancelled");
            assertTrue(res.processed() < count, "Mode " + mode + " should not have finished");
            if (mode == BatchRunner.Mode.SEQUENTIAL) {
                assertEquals(1, res.processed(), "Sequential should have processed exactly 1");
            }

            Set<Thread> alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("BatchRunner-Images-") || t.getName().startsWith("BatchRunner-S trips-"))
                .filter(Thread::isAlive)
                .collect(Collectors.toSet());
            assertTrue(alive.isEmpty(), "Pool threads still alive for " + mode + ": " + alive);
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
    void testBaselineCleanupNormal() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        runner.runSequentialBaseline(inputDir, ct);
        assertNotNull(runner.lastBaselineTempDir);
        assertFalse(Files.exists(runner.lastBaselineTempDir), "Temp dir should be deleted after normal run");
    }

    @Test
    void testBaselineCleanupCancelled() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        AtomicBoolean created = new AtomicBoolean(false);
        Path[] createdPath = new Path[1];

        runner.tempDirFactory = () -> {
            Path p = Files.createTempDirectory("baseline_cancel_");
            created.set(true);
            createdPath[0] = p;
            ct.cancel();
            return p;
        };

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, ct, true, null);

        assertTrue(created.get(), "TempDirFactory should have been called");
        assertEquals(createdPath[0], runner.lastBaselineTempDir);
        assertFalse(Files.exists(runner.lastBaselineTempDir), "Temp dir should be deleted after cancellation");
        assertTrue(comp.run().cancelled());
        assertNull(comp.baselineNanos());
        assertEquals(0.0, comp.speedup());
        try (var stream = Files.list(outputDir)) {
            assertEquals(0, stream.count(), "Output folder should be empty");
        }
    }

    @Test
    void testOrchestrationLogic() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        List<String> statuses = new ArrayList<>();
        Consumer<String> statusConsumer = statuses::add;

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, ct, true, statusConsumer);

        int idx1 = statuses.indexOf("Warming up...");
        int idx2 = statuses.indexOf("Running sequential baseline...");
        int idx3 = statuses.indexOf("Running PARALLEL_IMAGES...");
        assertTrue(idx1 >= 0 && idx2 >= 0 && idx3 >= 0, "All status messages must be present");
        assertTrue(idx1 < idx2 && idx2 < idx3, "Statuses should be in order");

        assertEquals(1, runner.getBaselineInvocationCount());
        assertNotNull(comp.run());
        assertNotNull(comp.baselineNanos());
        assertEquals((double)comp.baselineNanos() / comp.run().elapsedNanos(), comp.speedup(), 1e-9);
        assertEquals(comp.speedup() / 4, comp.efficiency(), 1e-9);
    }

    @Test
    void testOrchestrationSequential() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 4, null, ct, true, null);

        assertEquals(1.0, comp.speedup());
        assertEquals(0, runner.getBaselineInvocationCount());
        assertNull(comp.baselineNanos());
    }

    @Test
    void testOrchestrationBaselineFailure() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        runner.tempDirFactory = () -> {
            throw new IOException("disk full");
        };

        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, null, true, null);

        assertEquals(1, comp.run().processed());
        assertNull(comp.baselineNanos());
        assertTrue(comp.baselineWarning().contains("disk full"));
        assertTrue(Double.isNaN(comp.speedup()));
        assertTrue(Double.isNaN(comp.efficiency()));
    }

    @Test
    void testSpeedupSequential() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 4, null, ct, true, null);
        assertEquals(1.0, comp.speedup());
        assertEquals(0, runner.getBaselineInvocationCount());
        assertNull(comp.baselineNanos());
    }

    @Test
    void testSpeedupCancelled() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        ct.cancel();
        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, ct, true, null);
        assertEquals(0.0, comp.speedup());
    }

    @Test
    void testSpeedupNoCompare() throws IOException {
        createTestImage("img.png", 10, 10, 0);
        BatchRunner.CancelToken ct = new BatchRunner.CancelToken();
        BatchRunner.Comparison comp = runner.runWithComparison(
            BatchRunner.Mode.PARALLEL_IMAGES, inputDir, outputDir, 4, null, ct, false, null);
        assertTrue(Double.isNaN(comp.speedup()));
        assertNull(comp.baselineNanos());
        assertNull(comp.baselineWarning());
        assertEquals(0, runner.getBaselineInvocationCount());
    }
}
