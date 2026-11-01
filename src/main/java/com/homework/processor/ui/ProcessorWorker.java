package com.homework.processor.ui;

import com.homework.processor.app.BatchRunner;
import com.homework.processor.app.BatchRunner.Comparison;
import com.homework.processor.app.BatchRunner.CancelToken;

import javax.swing.*;
import java.util.List;

public class ProcessorWorker extends SwingWorker<Comparison, ProcessorWorker.ProgressUpdate> {

    public record ProgressUpdate(int done, int total, String phase) {}

    private final BatchRunner runner;
    private final BatchRunner.Mode mode;
    private final java.nio.file.Path inputDir;
    private final java.nio.file.Path outputDir;
    private final int threads;
    private final boolean compare;
    private final CancelToken cancelToken;
    private final java.util.function.Consumer<String> statusConsumer;
    private final ImageProcessorUI ui;

    public ProcessorWorker(ImageProcessorUI ui, BatchRunner runner, BatchRunner.Mode mode,
                           java.nio.file.Path inputDir, java.nio.file.Path outputDir,
                           int threads, boolean compare, CancelToken cancelToken) {
        this.ui = ui;
        this.runner = runner;
        this.mode = mode;
        this.inputDir = inputDir;
        this.outputDir = outputDir;
        this.threads = threads;
        this.compare = compare;
        this.cancelToken = cancelToken;
        this.statusConsumer = s -> publish(new ProgressUpdate(0, 0, s));
    }

    @Override
    protected Comparison doInBackground() throws Exception {
        return runner.runWithComparison(
            mode, inputDir, outputDir, threads,
            (done, total) -> publish(new ProgressUpdate(done, total, null)),
            cancelToken,
            (mode == BatchRunner.Mode.SEQUENTIAL) ? false : compare,
            statusConsumer
        );
    }

    @Override
    protected void process(List<ProgressUpdate> chunks) {
        for (ProgressUpdate update : chunks) {
            if (update.phase() != null) {
                ui.updateStatus(update.phase());
                ui.resetProgress();
            }
            if (update.done() > 0) {
                ui.updateProgress(update.done(), update.total());
            }
        }
    }

    @Override
    protected void done() {
        try {
            Comparison result = get();
            if (result != null) {
                if (result.run().cancelled()) {
                    ui.updateStatus("Cancelled");
                } else {
                    ui.updateStatus("Done");
                }
                ui.showResults(result);
            } else {
                ui.updateStatus("Cancelled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.updateStatus("Cancelled");
            ui.showError("Process interrupted: " + e.getMessage());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            String errorMsg = (cause != null) ? cause.getMessage() : e.getMessage();
            ui.updateStatus("Failed: " + errorMsg);
            if (cause instanceof IllegalArgumentException) {
                ui.showError(errorMsg);
            } else {
                ui.showError("Execution error: " + errorMsg);
            }
        } catch (Exception e) {
            ui.updateStatus("Failed: " + e.getMessage());
            ui.showError("Unexpected error: " + e.getMessage());
        } finally {
            ui.setUIState(false);
        }
    }
}
