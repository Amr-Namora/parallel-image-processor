package com.homework.processor.benchmark;

import com.homework.processor.core.*;
import com.homework.processor.parallel.*;
import com.homework.processor.sequential.SequentialProcessor;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

public class PipelinedCorrectnessTest {

    @Test
    public void testPipelinedPixelCorrectness() throws IOException {
        // Setup: 16 images with awkward sizes
        int numImages = 16;
        List<BufferedImage> sourceImages = new ArrayList<>();
        Random rnd = new Random(42);
        
        // Mix of sizes: some awkward, one larger
        int[][] sizes = {
            {97, 61}, {101, 73}, {127, 89}, {63, 111},
            {103, 107}, {131, 137}, {139, 149}, {151, 157},
            {163, 167}, {173, 179}, {181, 191}, {193, 197},
            {199, 211}, {223, 227}, {229, 233}, {1000, 1000}
        };

        for (int i = 0; i < numImages; i++) {
            int w = sizes[i][0];
            int h = sizes[i][1];
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
                }
            }
            sourceImages.add(img);
        }

        // Process using FULL_PIPELINED logic
        File tempDir = Files.createTempDirectory("pipelined_test_").toFile();
        ParallelImageProcessor processor = new ParallelImageProcessor(4, 4);
        SequentialProcessor seq = new SequentialProcessor();
        
        List<ImageBuffer> inputs = new ArrayList<>();
        for (BufferedImage img : sourceImages) {
            inputs.add(new ImageBuffer(img));
        }

        // Simulate the pipelined path: process buffers then write to disk
        ParallelImageProcessor.BatchResult batch = processor.processBuffers(inputs);
        ImageBuffer[] outputs = batch.results();

        for (int i = 0; i < outputs.length; i++) {
            BufferedImage outImg = new BufferedImage(outputs[i].getWidth(), outputs[i].getHeight(), BufferedImage.TYPE_INT_RGB);
            outImg.setRGB(0, 0, outputs[i].getWidth(), outputs[i].getHeight(), outputs[i].getPixels(), 0, outputs[i].getWidth());
            ImageIO.write(outImg, "png", new File(tempDir, "test_" + i + ".png"));
        }

        // Verify: read back and compare pixel by pixel with sequential
        for (int i = 0; i < numImages; i++) {
            // Pipelined result from disk
            BufferedImage diskImg = ImageIO.read(new File(tempDir, "test_" + i + ".png"));
            ImageBuffer diskBuf = new ImageBuffer(diskImg);

            // Sequential baseline
            ImageBuffer seqBuf = seq.process(inputs.get(i));

            // Pixel-by-pixel comparison (ignoring alpha as per requirements)
            int[] p1 = diskBuf.getPixels();
            int[] p2 = seqBuf.getPixels();
            
            assertEquals(p1.length, p2.length, "Pixel array length mismatch for image " + i);
            for (int p = 0; p < p1.length; p++) {
                if (p1[p] != p2[p]) {
                    fail("Pixel mismatch at index " + p + " in image " + i + 
                         ". Expected: " + Integer.toHexString(p2[p]) + 
                         ", Actual: " + Integer.toHexString(p1[p]));
                }
            }
        }
        
        // Cleanup
        for (File f : tempDir.listFiles()) f.delete();
        tempDir.delete();
    }
}
