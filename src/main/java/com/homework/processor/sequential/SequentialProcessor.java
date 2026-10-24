package com.homework.processor.sequential;

import com.homework.processor.core.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SequentialProcessor implements the single-threaded baseline.
 *
 * Justification:
 * This serves as the source of truth for correctness and the baseline for speedup.
 * It processes images one by one, and each filter is applied to the entire image
 * in a single pass.
 */
public class SequentialProcessor {
    private final List<Filter> pipeline;

    public SequentialProcessor() {
        this.pipeline = new ArrayList<>();
        // Default pipeline: Blur then Sobel
        this.pipeline.add(new GaussianBlurFilter());
        this.pipeline.add(new SobelEdgeFilter());
    }

    public ImageBuffer process(ImageBuffer input) {
        ImageBuffer currentInput = input;

        for (Filter filter : pipeline) {
            ImageBuffer output = new ImageBuffer(currentInput.getWidth(), currentInput.getHeight());
            // Sequential run: process from row 0 to height
            filter.apply(currentInput, output, 0, currentInput.getHeight());
            currentInput = output;
        }

        return currentInput;
    }

    public List<Filter> getPipeline() {
        return pipeline;
    }
}
