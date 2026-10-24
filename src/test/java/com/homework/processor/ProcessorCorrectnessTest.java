package com.homework.processor;

import com.homework.processor.core.*;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness tests for the Image Processing pipeline.
 *
 * Alpha handling:
 * All filters are designed to output an alpha value of 0xFF (fully opaque),
 * ignoring the alpha channel of the input images.
 */
public class ProcessorCorrectnessTest {

    /**
     * Arithmetic for 3x3 Gaussian Blur:
     * Input: 3x3 image, all pixels = 100 (grayscale)
     * Center pixel (1,1) kernel:
     * (1*100 + 2*100 + 1*100) + (2*100 + 4*100 + 2*100) + (1*100 + 2*100 + 1*100) = 1600
     * Result = 1600 / 16 = 100.
     * Expected: All pixels = 100.
     */
    @Test
    @DisplayName("Hand-computed 3x3 Uniform Image")
    void testSmallImageUniform() {
        int size = 3;
        ImageBuffer input = new ImageBuffer(size, size);
        int gray100 = (0xFF << 24) | (100 << 16) | (100 << 8) | 100;
        for (int i = 0; i < size * size; i++) input.getPixels()[i] = gray100;

        GaussianBlurFilter blur = new GaussianBlurFilter();
        ImageBuffer output = new ImageBuffer(size, size);
        blur.apply(input, output, 0, size);

        for (int p : output.getPixels()) {
            assertEquals(gray100, p, "Uniform image should remain uniform after blur");
        }
    }

    /**
     * Arithmetic for 3x3 Sobel:
     * Input: Vertical edge (left side 0, right side 255)
     * [ 0, 255, 255 ]
     * [ 0, 255, 255 ]
     * [ 0, 255, 255 ]
     *
     * Center pixel (1,1):
     * Gx = (-1*0 + 0*255 + 1*255) + (-2*0 + 0*255 + 2*255) + (-1*0 + 0*255 + 1*255)
     *    = 255 + 510 + 255 = 1020
     * Gy = (-1*0 + -2*255 + -1*255) + (0*0 + 0*255 + 0*255) + (1*0 + 2*255 + 1*255)
     *    = (-765) + 0 + (765) = 0
     * Magnitude = sqrt(1020^2 + 0^2) = 1020 -> clamp to 255.
     */
    @Test
    @DisplayName("Hand-computed 3x3 Sobel Vertical Edge")
    void testSobelVerticalEdge() {
        ImageBuffer input = new ImageBuffer(3, 3);
        int black = (0xFF << 24) | (0 << 16) | (0 << 8) | 0;
        int white = (0xFF << 24) | (255 << 16) | (255 << 8) | 255;

        // Column 0 = black, Col 1&2 = white
        for (int y = 0; y < 3; y++) {
            input.setPixel(0, y, black);
            input.setPixel(1, y, white);
            input.setPixel(2, y, white);
        }

        SobelEdgeFilter sobel = new SobelEdgeFilter();
        ImageBuffer output = new ImageBuffer(3, 3);
        sobel.apply(input, output, 0, 3);

        int centerPixel = output.getPixel(1, 1);
        int expected = (0xFF << 24) | (255 << 16) | (255 << 8) | 255;
        assertEquals(expected, centerPixel, "Vertical edge should produce max magnitude at center");
    }

    @Test
    @DisplayName("Extreme Dimensions: 1x1")
    void testSinglePixel() {
        ImageBuffer input = new ImageBuffer(1, 1);
        int color = (0xFF << 24) | (128 << 16) | (128 << 8) | 128;
        input.setPixel(0, 0, color);

        GaussianBlurFilter blur = new GaussianBlurFilter();
        ImageBuffer output = new ImageBuffer(1, 1);
        blur.apply(input, output, 0, 1);
        assertEquals(color, output.getPixel(0, 0), "1x1 blur should preserve color");
    }

    @Test
    @DisplayName("Extreme Dimensions: 1x10 and 10x1")
    void testThinImages() {
        // 1x10
        ImageBuffer thinV = new ImageBuffer(1, 10);
        GaussianBlurFilter blur = new GaussianBlurFilter();
        ImageBuffer outV = new ImageBuffer(1, 10);
        assertDoesNotThrow(() -> blur.apply(thinV, outV, 0, 10));

        // 10x1
        ImageBuffer thinH = new ImageBuffer(10, 1);
        ImageBuffer outH = new ImageBuffer(10, 1);
        assertDoesNotThrow(() -> blur.apply(thinH, outH, 0, 1));
    }

    @Test
    @DisplayName("Image smaller than kernel (2x2)")
    void testKernelSizedImage() {
        ImageBuffer input = new ImageBuffer(2, 2);
        GaussianBlurFilter blur = new GaussianBlurFilter();
        ImageBuffer output = new ImageBuffer(2, 2);
        assertDoesNotThrow(() -> blur.apply(input, output, 0, 2));
    }

    @Test
    @DisplayName("Non-divisible height and strip-consistency")
    void testNonDivisibleHeight() {
        int width = 10;
        int height = 13; // 13 is prime, not divisible by 2, 4, 8...
        ImageBuffer input = new ImageBuffer(width, height);
        // Fill with random data
        for (int i = 0; i < width * height; i++) {
            input.getPixels()[i] = (0xFF << 24) | (int)(Math.random() * 0xFFFFFF);
        }

        GaussianBlurFilter blur = new GaussianBlurFilter();

        // Full range run
        ImageBuffer fullOutput = new ImageBuffer(width, height);
        blur.apply(input, fullOutput, 0, height);

        // Strip run (4 strips)
        ImageBuffer stripOutput = new ImageBuffer(width, height);
        int numStrips = 4;
        int stripSize = (int) Math.ceil((double) height / numStrips);

        for (int i = 0; i < numStrips; i++) {
            int startY = i * stripSize;
            int endY = Math.min(startY + stripSize, height);
            if (startY < height) {
                blur.apply(input, stripOutput, startY, endY);
            }
        }

        for (int i = 0; i < width * height; i++) {
            assertEquals(fullOutput.getPixels()[i], stripOutput.getPixels()[i],
                "Strip-based processing must be pixel-identical to full processing");
        }
    }
}
