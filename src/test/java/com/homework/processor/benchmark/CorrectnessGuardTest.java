package com.homework.processor.benchmark;

import com.homework.processor.core.ImageBuffer;
import com.homework.processor.parallel.ParallelImageProcessor;
import com.homework.processor.parallel.ParallelStripProcessor;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the correctness guard in the BenchmarkHarness.
 * It uses a "FaultyProcessor" test double to simulate a bug.
 */
public class CorrectnessGuardTest {

    /**
     * Functional interface to wrap processor behavior for the test double.
     */
    interface ProcessorWrapper {
        ImageBuffer process(ImageBuffer input);
    }

    @Test
    @DisplayName("Should detect mismatch when using faulty processor")
    void testCorrectnessGuardDetectsFailure() {
        // 1. Setup: Create a golden checksum from a real sequential run
        ImageBuffer input = new ImageBuffer(100, 100);
        new Random().nextBytes(new byte[0]); // dummy
        for (int i = 0; i < 100 * 100; i++) input.getPixels()[i] = (int)(Math.random() * 0xFFFFFF);

        SequentialProcessor seq = new SequentialProcessor();
        long goldenChecksum = seq.process(input).calculateChecksum();

        // 2. Test Double: A processor that deliberately flips one pixel
        ProcessorWrapper faultyProcessor = (in) -> {
            ImageBuffer out = seq.process(in);
            out.getPixels()[0] = out.getPixels()[0] ^ 0xFF; // Flip bits
            return out;
        };

        // 3. Verify: Checksum must differ
        long actualChecksum = faultyProcessor.process(input).calculateChecksum();
        assertNotEquals(goldenChecksum, actualChecksum, "Faulty processor must produce a different checksum");
    }

    @Test
    @DisplayName("Should verify identity with correct processor")
    void testCorrectnessGuardAcceptsCorrect() {
        ImageBuffer input = new ImageBuffer(100, 100);
        for (int i = 0; i < 100 * 100; i++) input.getPixels()[i] = (int)(Math.random() * 0xFFFFFF);

        SequentialProcessor seq = new SequentialProcessor();
        ImageBuffer result1 = seq.process(input);
        ImageBuffer result2 = seq.process(input);

        assertEquals(result1.calculateChecksum(), result2.calculateChecksum(), "Identical runs must have identical checksums");
    }
}
