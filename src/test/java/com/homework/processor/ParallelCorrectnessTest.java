package com.homework.processor;

import com.homework.processor.core.*;
import com.homework.processor.parallel.ParallelImageProcessor;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ParallelCorrectnessTest {

    /**
     * Helper to create a list of dummy ImageBuffers.
     */
    private List<ImageBuffer> createDummyInputs(int count, int w, int h) {
        List<ImageBuffer> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ImageBuffer buf = new ImageBuffer(w, h);
            // Fill with predictable pattern
            for (int j = 0; j < w * h; j++) {
                buf.getPixels()[j] = (0xFF << 24) | (i * 10 % 256 << 16) | (j % 256 << 8) | (i + j % 256);
            }
            inputs.add(buf);
        }
        return inputs;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 100}) // N=100 tests cases where N > numImages
    @DisplayName("Parallel vs Sequential Pixel-Identity (Repeat 50x)")
    void testParallelIdentity(int numThreads) {
        int numImages = 10;
        int w = 64, h = 64;
        List<ImageBuffer> inputs = createDummyInputs(numImages, w, h);

        SequentialProcessor seqProc = new SequentialProcessor();

        // Run the parallel test 50 times to catch non-deterministic race conditions
        for (int run = 0; run < 50; run++) {
            ParallelImageProcessor parProc = new ParallelImageProcessor(numThreads, 4);
            ParallelImageProcessor.BatchResult result = parProc.processBuffers(inputs);

            assertEquals(numImages, result.processedCount(), "All images should be processed");
            assertEquals(0, result.failedImages().size(), "No failures expected");

            for (int i = 0; i < numImages; i++) {
                ImageBuffer seqResult = seqProc.process(inputs.get(i));
                ImageBuffer parResult = result.results()[i];

                assertArrayEquals(seqResult.getPixels(), parResult.getPixels(),
                    "Mismatch at image " + i + " run " + run + " with threads " + numThreads);
            }
        }
    }

    @Test
    @DisplayName("Edge Case: Zero Images")
    void testZeroImages() {
        ParallelImageProcessor parProc = new ParallelImageProcessor(4, 4);
        ParallelImageProcessor.BatchResult result = parProc.processBuffers(Collections.emptyList());
        assertEquals(0, result.processedCount());
        assertEquals(0, result.results().length);
    }

    @Test
    @DisplayName("Edge Case: One Image")
    void testOneImage() {
        List<ImageBuffer> inputs = createDummyInputs(1, 32, 32);
        ParallelImageProcessor parProc = new ParallelImageProcessor(4, 4);
        ParallelImageProcessor.BatchResult result = parProc.processBuffers(inputs);
        assertEquals(1, result.processedCount());
        assertNotNull(result.results()[0]);
    }

    @Test
    @DisplayName("Semaphore Boundary: Permits = 1")
    void testSinglePermit() {
        List<ImageBuffer> inputs = createDummyInputs(5, 32, 32);
        ParallelImageProcessor parProc = new ParallelImageProcessor(4, 1);
        ParallelImageProcessor.BatchResult result = parProc.processBuffers(inputs);
        assertEquals(5, result.processedCount());
    }
}
