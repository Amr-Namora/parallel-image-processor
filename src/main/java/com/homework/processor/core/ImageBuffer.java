package com.homework.processor.core;

import java.util.Random;

/**
 * ImageBuffer provides a raw 1D array representation of an image.
 */
public class ImageBuffer {
    private final int width;
    private final int height;
    private final int[] pixels;

    public ImageBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public ImageBuffer(java.awt.image.BufferedImage image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int[] getPixels() {
        return pixels;
    }

    public void setPixel(int x, int y, int color) {
        pixels[y * width + x] = color;
    }

    public int getPixel(int x, int y) {
        return pixels[y * width + x];
    }

    /**
     * Computes a position-dependent checksum of the image pixels.
     * Uses a rolling hash logic to detect swapped strips or shifted rows.
     */
    public long calculateChecksum() {
        long sum = 0;
        long multiplier = 31; // Large odd constant
        for (int i = 0; i < pixels.length; i++) {
            // mix position and value
            sum = sum * multiplier + (pixels[i] ^ i);
        }
        return sum;
    }
}
