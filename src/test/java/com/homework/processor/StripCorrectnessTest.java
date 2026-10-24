package com.homework.processor;

import com.homework.processor.core.*;
import com.homework.processor.parallel.ParallelStripProcessor;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class StripCorrectnessTest {

    private ImageBuffer createRandomImage(int w, int h) {
        ImageBuffer buf = new ImageBuffer(w, h);
        Random rand = new Random();
        for (int i = 0; i < w * h; i++) {
            buf.getPixels()[i] = (0xFF << 24) | rand.nextInt(0xFFFFFF);
        }
        return buf;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    @DisplayName("Large Image Pixel-Identity (Repeat 50x)")
    void testLargeImageIdentity(int numThreads) {
        int w = 2000, h = 1500;
        ImageBuffer input = createRandomImage(w, h);
        SequentialProcessor seqProc = new SequentialProcessor();
        ImageBuffer expected = seqProc.process(input);

        // Use a strip size that is not perfectly divisible
        int rowsPerStrip = 100;
        ParallelStripProcessor stripProc = new ParallelStripProcessor(numThreads, rowsPerStrip);

        for (int run = 0; run < 50; run++) {
            ImageBuffer actual = stripProc.processFixed(input);
            assertArrayEquals(expected.getPixels(), actual.getPixels(),
                "Mismatch at run " + run + " with threads " + numThreads);
        }
    }

    @Test
    @DisplayName("Edge Case: Strips > Height")
    void testStripsLargerThanHeight() {
        int w = 10, h = 5;
        ImageBuffer input = createRandomImage(w, h);
        SequentialProcessor seqProc = new SequentialProcessor();
        ImageBuffer expected = seqProc.process(input);

        // rowsPerStrip = 10, height = 5. Only 1 strip will actually run.
        ParallelStripProcessor stripProc = new ParallelStripProcessor(4, 10);
        ImageBuffer actual = stripProc.processFixed(input);

        assertArrayEquals(expected.getPixels(), actual.getPixels());
    }

    @Test
    @DisplayName("Edge Case: Height = 1")
    void testHeightOne() {
        int w = 10, h = 1;
        ImageBuffer input = createRandomImage(w, h);
        SequentialProcessor seqProc = new SequentialProcessor();
        ImageBuffer expected = seqProc.process(input);

        ParallelStripProcessor stripProc = new ParallelStripProcessor(4, 1);
        ImageBuffer actual = stripProc.processFixed(input);

        assertArrayEquals(expected.getPixels(), actual.getPixels());
    }

    @Test
    @DisplayName("Edge Case: Height Not Divisible by Strip Count")
    void testNonDivisibleHeight() {
        int w = 10, h = 13;
        ImageBuffer input = createRandomImage(w, h);
        SequentialProcessor seqProc = new SequentialProcessor();
        ImageBuffer expected = seqProc.process(input);

        // 3 rows per strip, 13 height -> 5 strips (last one is 1 row)
        ParallelStripProcessor stripProc = new ParallelStripProcessor(4, 3);
        ImageBuffer actual = stripProc.processFixed(input);

        assertArrayEquals(expected.getPixels(), actual.getPixels());
    }

    @Test
    @DisplayName("Edge Case: Image smaller than kernel")
    void testTinyImage() {
        int w = 2, h = 2;
        ImageBuffer input = createRandomImage(w, h);
        SequentialProcessor seqProc = new SequentialProcessor();
        ImageBuffer expected = seqProc.process(input);

        ParallelStripProcessor stripProc = new ParallelStripProcessor(4, 1);
        ImageBuffer actual = stripProc.processFixed(input);

        assertArrayEquals(expected.getPixels(), actual.getPixels());
    }
}
