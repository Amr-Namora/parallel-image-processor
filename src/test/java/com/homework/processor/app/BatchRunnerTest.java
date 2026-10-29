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
        // 1. Regular small image (97x61)
        createTestImage("small.png", 97, 61, 0x112233);
        // 2. Very short image (height < 64)
        createTestImage("short.png", 100, 20, 0x445566);
        // 3. 1-pixel high
        createTestImage("oneH.png", 100, 1, 0x778899);
        // 4. 1-pixel wide
        createTestImage("oneW.png", 1, 100, 0xAABBCC);
        // 5. Large image
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

        // Test Sequential Progress
        runner.run(BatchRunner.Mode.SEQUENTIAL, inputDir, outputDir, 1, l, null);
        assertEquals(count, lastProgress.get());
        assertEquals(count, callCount.get());

        // Test Parallel Image Progress
        Path pOut = Files.createTempDirectory("pout");
        callCount.set(0);
        runner.run(BatchRunner.Mode.PARALLEL_IMAGES, inputDir, pOut, 4, l, null);
        assertEquals(count, lastProgress.get());
        assertEquals(count, callCount.get());

        // Test Parallel Strip Progress
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
}
