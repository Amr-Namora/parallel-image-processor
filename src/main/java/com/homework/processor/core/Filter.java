package com.homework.processor.core;

/**
 * Filter defines a generic 2D convolution-style image processing operation.
 *
 * All filters must operate on the immutable input buffer and write results
 * to a separate output buffer to ensure correctness during parallel execution.
 */
public interface Filter {
    /**
     * Applies the filter to the input buffer and stores the result in the output buffer.
     *
     * @param input  The source image buffer.
     * @param output The destination image buffer.
     * @param startY The starting row index (used for strip-based parallelism).
     * @param endY   The ending row index (exclusive).
     */
    void apply(ImageBuffer input, ImageBuffer output, int startY, int endY);
}
