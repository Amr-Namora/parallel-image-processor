package com.homework.processor.core;

/**
 * GaussianBlurFilter implements a simple 3x3 Gaussian blur.
 *
 * Justification:
 * Gaussian blur is a 2D convolution. To avoid floating point overhead,
 * we use integer weights.
 *
 * Optimization:
 * To avoid clamping overhead in the hot loop, we process the interior
 * pixels separately from the borders.
 *
 * Alpha Handling:
 * We treat the images as fully opaque (alpha = 0xFF) for the processed results.
 */
public class GaussianBlurFilter implements Filter {
    private static final int[] KERNEL = {
        1, 2, 1,
        2, 4, 2,
        1, 2, 1
    };
    private static final int KERNEL_SUM = 16;

    @Override
    public void apply(ImageBuffer input, ImageBuffer output, int startY, int endY) {
        int width = input.getWidth();
        int height = input.getHeight();

        for (int y = startY; y < endY; y++) {
            // Handle border rows separately to remove clamping from inner loop
            if (y == 0 || y == height - 1) {
                processRowWithClamping(input, output, y, width, height);
            } else {
                // Hot loop: Interior pixels
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

    private void processInteriorPixel(ImageBuffer input, ImageBuffer output, int x, int y, int width) {
        int rSum = 0, gSum = 0, bSum = 0;

        // Unrolled/Direct access for interior pixels (no clamping needed)
        // Row y-1
        int p00 = input.getPixel(x - 1, y - 1);
        int p01 = input.getPixel(x, y - 1);
        int p02 = input.getPixel(x + 1, y - 1);
        // Row y
        int p10 = input.getPixel(x - 1, y);
        int p11 = input.getPixel(x, y);
        int p12 = input.getPixel(x + 1, y);
        // Row y+1
        int p20 = input.getPixel(x - 1, y + 1);
        int p21 = input.getPixel(x, y + 1);
        int p22 = input.getPixel(x + 1, y + 1);

        rSum += ((p00 >> 16) & 0xFF) * 1 + ((p01 >> 16) & 0xFF) * 2 + ((p02 >> 16) & 0xFF) * 1;
        rSum += ((p10 >> 16) & 0xFF) * 2 + ((p11 >> 16) & 0xFF) * 4 + ((p12 >> 16) & 0xFF) * 2;
        rSum += ((p20 >> 16) & 0xFF) * 1 + ((p21 >> 16) & 0xFF) * 2 + ((p22 >> 16) & 0xFF) * 1;

        gSum += ((p00 >> 8) & 0xFF) * 1 + ((p01 >> 8) & 0xFF) * 2 + ((p02 >> 8) & 0xFF) * 1;
        gSum += ((p10 >> 8) & 0xFF) * 2 + ((p11 >> 8) & 0xFF) * 4 + ((p12 >> 8) & 0xFF) * 2;
        gSum += ((p20 >> 8) & 0xFF) * 1 + ((p21 >> 8) & 0xFF) * 2 + ((p22 >> 8) & 0xFF) * 1;

        bSum += (p00 & 0xFF) * 1 + (p01 & 0xFF) * 2 + (p02 & 0xFF) * 1;
        bSum += (p10 & 0xFF) * 2 + (p11 & 0xFF) * 4 + (p12 & 0xFF) * 2;
        bSum += (p20 & 0xFF) * 1 + (p21 & 0xFF) * 2 + (p22 & 0xFF) * 1;

        output.setPixel(x, y, (0xFF << 24) | ((rSum / KERNEL_SUM) << 16) | ((gSum / KERNEL_SUM) << 8) | (bSum / KERNEL_SUM));
    }

    private void processRowWithClamping(ImageBuffer input, ImageBuffer output, int y, int width, int height) {
        for (int x = 0; x < width; x++) {
            processPixelWithClamping(input, output, x, y, width, height);
        }
    }

    private void processPixelWithClamping(ImageBuffer input, ImageBuffer output, int x, int y, int width, int height) {
        int rSum = 0, gSum = 0, bSum = 0;
        for (int ky = -1; ky <= 1; ky++) {
            for (int kx = -1; kx <= 1; kx++) {
                int ix = Math.min(Math.max(x + kx, 0), width - 1);
                int iy = Math.min(Math.max(y + ky, 0), height - 1);
                int pixel = input.getPixel(ix, iy);
                int weight = KERNEL[(ky + 1) * 3 + (kx + 1)];
                rSum += ((pixel >> 16) & 0xFF) * weight;
                gSum += ((pixel >> 8) & 0xFF) * weight;
                bSum += (pixel & 0xFF) * weight;
            }
        }
        output.setPixel(x, y, (0xFF << 24) | ((rSum / KERNEL_SUM) << 16) | ((gSum / KERNEL_SUM) << 8) | (bSum / KERNEL_SUM));
    }
}
