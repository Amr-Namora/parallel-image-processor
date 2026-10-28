package com.homework.processor.parallel;

import com.homework.processor.core.*;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that processors are stateless and can be shared across threads.
 */
public class ProcessorConcurrencyTest {

    @RepeatedTest(10)
    public void testParallelImageProcessorStatelessness() throws Exception {
        int numThreads = 8;
        int numImages = 20;
        ParallelImageProcessor sharedProcessor = new ParallelImageProcessor(numThreads, 4);
        SequentialProcessor seq = new SequentialProcessor();

        // Create a set of random images
        List<ImageBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < numImages; i++) {
            buffers.add(createRandomImageBuffer(100, 100));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (ImageBuffer buf : buffers) {
            futures.add(executor.submit(() -> {
                // We use a subset of 1 image to simulate shared processor call
                ParallelImageProcessor.BatchResult result = sharedProcessor.processBuffers(Collections.singletonList(buf));
                ImageBuffer parallelOut = result.results()[0];
                ImageBuffer sequentialOut = seq.process(buf);
                return parallelOut.calculateChecksum() == sequentialOut.calculateChecksum();
            }));
        }

        for (Future<Boolean> f : futures) {
            assertTrue(f.get(), "Parallel output should be identical to sequential output");
        }
        executor.shutdown();
    }

    @RepeatedTest(10)
    public void testParallelStripProcessorStatelessness() throws Exception {
        int numThreads = 8;
        int rowsPerStrip = 16;
        int numImages = 20;
        ParallelStripProcessor sharedProcessor = new ParallelStripProcessor(numThreads, rowsPerStrip);
        SequentialProcessor seq = new SequentialProcessor();

        List<ImageBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < numImages; i++) {
            buffers.add(createRandomImageBuffer(200, 200));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (ImageBuffer buf : buffers) {
            futures.add(executor.submit(() -> {
                ImageBuffer parallelOut = sharedProcessor.processFixed(buf);
                ImageBuffer sequentialOut = seq.process(buf);
                return parallelOut.calculateChecksum() == sequentialOut.calculateChecksum();
            }));
        }

        for (Future<Boolean> f : futures) {
            assertTrue(f.get(), "Parallel strip output should be identical to sequential output");
        }
        executor.shutdown();
    }

    private ImageBuffer createRandomImageBuffer(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        return new ImageBuffer(img);
    }
}
