package com.homework.processor.benchmark;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * DatasetGenerator creates synthetic high-resolution images for benchmarking.
 * It uses fixed seeds to ensure reproducibility and patterns that compress realistically.
 */
public class DatasetGenerator {

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: DatasetGenerator <count> <width> <height> <outputDir> [seed]");
            System.exit(1);
        }

        int count = Integer.parseInt(args[0]);
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        String outputDir = args[3];
        long seed = (args.length > 4) ? Long.parseLong(args[4]) : 42L;

        try {
            generateDataset(count, width, height, outputDir, seed);
            System.out.println("Successfully generated " + count + " images in " + outputDir);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void generateDataset(int count, int width, int height, String outputDir, long seed) throws IOException {
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        Random random = new Random(seed);

        for (int i = 0; i < count; i++) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            // 1. Background Gradient
            int r1 = random.nextInt(256), g1 = random.nextInt(256), b1 = random.nextInt(256);
            int r2 = random.nextInt(256), g2 = random.nextInt(256), b2 = random.nextInt(256);

            for (int y = 0; y < height; y++) {
                float ratio = (float) y / height;
                int r = (int) (r1 * (1 - ratio) + r2 * ratio);
                int g = (int) (g1 * (1 - ratio) + g2 * ratio);
                int b = (int) (b1 * (1 - ratio) + b2 * ratio);
                g2d.setColor(new Color(r, g, b));
                g2d.drawLine(0, y, width, y);
            }

            // 2. Random Geometric Shapes (to create edges for Sobel)
            for (int s = 0; s < 10; s++) {
                g2d.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                int type = random.nextInt(3);
                int x = random.nextInt(width);
                int y = random.nextInt(height);
                int w = random.nextInt(width / 4);
                int h = random.nextInt(height / 4);

                if (type == 0) g2d.fillRect(x, y, w, h);
                else if (type == 1) g2d.drawRect(x, y, w, h);
                else g2d.fillOval(x, y, w, h);
            }

            // 3. Mild Structured Noise
            for (int n = 0; n < 1000; n++) {
                g2d.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), 50));
                g2d.fillRect(random.nextInt(width), random.nextInt(height), 2, 2);
            }

            g2d.dispose();

            File outputFile = new File(dir, String.format("image_%03d.png", i));
            ImageIO.write(image, "png", outputFile);
        }
    }
}
