package com.homework.processor.core;

/**
 * SobelEdgeFilter implements the Sobel operator for edge detection.
 *
 * Justification:
 * Sobel is a 2D convolution using two kernels (Gx and Gy) to find
 * intensity gradients.
 */
public class SobelEdgeFilter implements Filter {
    private static final int[] GX = {
        -1, 0, 1,
        -2, 0, 2,
        -1, 0, 1
    };
    private static final int[] GY = {
        -1, -2, -1,
         0,  0,  0,
         1,  2,  1
    };

    @Override
    public void apply(ImageBuffer input, ImageBuffer output, int startY, int endY) {
        int width = input.getWidth();
        int height = input.getHeight();

        for (int y = startY; y < endY; y++) {
            if (y == 0 || y == height - 1) {
                processRowWithClamping(input, output, y, width, height);
            } else {
                for (int x = 0; x < width; x++) {
                    if (x == 0 || x == width - 1) {
                        processPixelWithClamping(input, output, x, y, width, height);
                    } else {
                        processInteriorPixel(input, output, x, y, width);
                    }
                }
            }
        }
    }

    private int getLuminance(int pixel) {
        return (((pixel >> 16) & 0xFF) + ((pixel >> 8) & 0xFF) + (pixel & 0xFF)) / 3;
    }

    private void processInteriorPixel(ImageBuffer input, ImageBuffer output, int x, int y, int width) {
        int gxSum = 0;
        int gySum = 0;

        int rowAbove = (y - 1) * width;
        int rowCurrent = y * width;
        int rowBelow = (y + 1) * width;

        // Gx
        gxSum += getLuminance(input.getPixels()[rowAbove + x - 1]) * -1 + getLuminance(input.getPixels()[rowAbove + x]) * 0 + getLuminance(input.getPixels()[rowAbove + x + 1]) * 1;
        gxSum += getLuminance(input.getPixels()[rowCurrent + x - 1]) * -2 + getLuminance(input.getPixels()[rowCurrent + x]) * 0 + getLuminance(input.getPixels()[rowCurrent + x + 1]) * 2;
        gxSum += getLuminance(input.getPixels()[rowBelow + x - 1]) * -1 + getLuminance(input.getPixels()[rowBelow + x]) * 0 + getLuminance(input.getPixels()[rowBelow + x + 1]) * 1;

        // Gy
        gySum += getLuminance(input.getPixels()[rowAbove + x - 1]) * -1 + getLuminance(input.getPixels()[rowAbove + x]) * -2 + getLuminance(input.getPixels()[rowAbove + x + 1]) * -1;
        gySum += getLuminance(input.getPixels()[rowCurrent + x - 1]) * 0 + getLuminance(input.getPixels()[rowCurrent + x]) * 0 + getLuminance(input.getPixels()[rowCurrent + x + 1]) * 0;
        gySum += getLuminance(input.getPixels()[rowBelow + x - 1]) * 1 + getLuminance(input.getPixels()[rowBelow + x]) * 2 + getLuminance(input.getPixels()[rowBelow + x + 1]) * 1;

        int magnitude = (int) Math.sqrt(gxSum * gxSum + gySum * gySum);
        magnitude = Math.min(255, magnitude);
        output.setPixel(x, y, (0xFF << 24) | (magnitude << 16) | (magnitude << 8) | magnitude);
    }

    private void processRowWithClamping(ImageBuffer input, ImageBuffer output, int y, int width, int height) {
        for (int x = 0; x < width; x++) {
            processPixelWithClamping(input, output, x, y, width, height);
        }
    }

    private void processPixelWithClamping(ImageBuffer input, ImageBuffer output, int x, int y, int width, int height) {
        int gxSum = 0;
        int gySum = 0;

        for (int ky = -1; ky <= 1; ky++) {
            for (int kx = -1; kx <= 1; kx++) {
                int ix = Math.min(Math.max(x + kx, 0), width - 1);
                int iy = Math.min(Math.max(y + ky, 0), height - 1);
                int grayVal = getLuminance(input.getPixel(ix, iy));

                gxSum += grayVal * GX[(ky + 1) * 3 + (kx + 1)];
                gySum += grayVal * GY[(ky + 1) * 3 + (kx + 1)];
            }
        }

        int magnitude = (int) Math.sqrt(gxSum * gxSum + gySum * gySum);
        magnitude = Math.min(255, magnitude);
        output.setPixel(x, y, (0xFF << 24) | (magnitude << 16) | (magnitude << 8) | magnitude);
    }
}
