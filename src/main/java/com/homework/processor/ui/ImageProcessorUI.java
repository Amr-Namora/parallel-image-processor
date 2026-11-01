package com.homework.processor.ui;

import com.homework.processor.app.BatchRunner;
import com.homework.processor.app.BatchRunner.Comparison;
import com.homework.processor.app.BatchRunner.RunResult;
import com.homework.processor.app.BatchRunner.CancelToken;
import com.homework.processor.app.BatchRunner.FailedFile;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ImageProcessorUI extends JFrame {
    // BatchRunner instance kept for the life of the window
    private final BatchRunner runner = new BatchRunner();
    private ProcessorWorker currentWorker;
    private CancelToken currentCancelToken;

    // Folders Panel
    private JTextField txtInputFolder;
    private JTextField txtOutputFolder;

    // Settings Panel
    private JComboBox<String> comboMode;
    private JSpinner spinnerThreads;
    private JCheckBox checkCompare;
    private JLabel lblCores;

    // Run Panel
    private JButton btnStart;
    private JButton btnCancel;
    private JProgressBar progressBar;
    private JLabel lblStatus;
    private JLabel lblClock;
    private Timer clockTimer;

    // Results Panel
    private JLabel lblElapsed, lblSequential, lblSpeedup, lblEfficiency, lblProcessed, lblFailed;
    private JTextArea txtLogs;

    public ImageProcessorUI() {
        setTitle("Image Processor");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 560));

        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        mainPanel.add(createTitledPanel("Folders", createFoldersPanel()));
        mainPanel.add(createTitledPanel("Settings", createSettingsPanel()));
        mainPanel.add(createTitledPanel("Run", createRunPanel()));
        mainPanel.add(createTitledPanel("Results", createResultsPanel()));

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        add(scrollPane, BorderLayout.CENTER);

        initClock();
        pack();

        Dimension size = getSize();
        setSize(Math.max(size.width, 760), Math.max(size.height, 560));
        setLocationRelativeTo(null);

        setupCloseHandler();
    }

    private void setupCloseHandler() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (currentWorker != null && !currentWorker.isDone()) {
                    if (currentCancelToken != null) {
                        currentCancelToken.cancel();
                    }
                    setVisible(false);

                    // Wait for worker in a daemon thread to avoid blocking EDT
                    Thread exitThread = new Thread(() -> {
                        try {
                            currentWorker.get(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ignore) {
                        }
                        System.exit(0);
                    });
                    exitThread.setDaemon(true);
                    exitThread.start();
                } else {
                    dispose();
                    System.exit(0);
                }
            }
        });
    }

    private JPanel createTitledPanel(String title, JPanel content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private JPanel createFoldersPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        p.add(new JLabel("Input Folder:"), gbc);

        txtInputFolder = new JTextField(20);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(txtInputFolder, gbc);

        JButton btnBrowseInput = new JButton("Browse");
        btnBrowseInput.addActionListener(e -> browseFolder(txtInputFolder));
        gbc.gridx = 2; gbc.weightx = 0;
        p.add(btnBrowseInput, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        p.add(new JLabel("Output Folder:"), gbc);

        txtOutputFolder = new JTextField(20);
        gbc.gridx = 1;
        p.add(txtOutputFolder, gbc);

        JButton btnBrowseOutput = new JButton("Browse");
        btnBrowseOutput.addActionListener(e -> browseFolder(txtOutputFolder));
        gbc.gridx = 2;
        p.add(btnBrowseOutput, gbc);

        return p;
    }

    private JPanel createSettingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        p.add(new JLabel("Mode:"), gbc);

        String[] modes = {"Sequential", "Parallel Images", "Parallel Strips"};
        comboMode = new JComboBox<>(modes);
        comboMode.addActionListener(e -> updateSettingsEnablement());
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(comboMode, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        p.add(new JLabel("Threads:"), gbc);

        int cores = Runtime.getRuntime().availableProcessors();
        spinnerThreads = new JSpinner(new SpinnerNumberModel(Math.max(1, cores / 2), 1, 16, 1));
        gbc.gridx = 1;
        gbc.weightx = 0;
        p.add(spinnerThreads, gbc);

        lblCores = new JLabel(cores + " logical cores detected");
        gbc.gridx = 2;
        p.add(lblCores, gbc);

        checkCompare = new JCheckBox("Compare with sequential baseline");
        checkCompare.setSelected(true);
        gbc.gridx = 1; gbc.gridy = 2;
        gbc.weightx = 1.0;
        p.add(checkCompare, gbc);

        updateSettingsEnablement();
        return p;
    }

    private JPanel createRunPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnStart = new JButton("Start");
        btnCancel = new JButton("Cancel");
        btnCancel.setEnabled(false);
        btnStart.addActionListener(e -> handleStart());
        btnCancel.addActionListener(e -> handleCancel());
        btnPanel.add(btnStart);
        btnPanel.add(btnCancel);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0;
        p.add(btnPanel, gbc);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        gbc.gridx = 0; gbc.gridy = 1;
        p.add(progressBar, gbc);

        lblStatus = new JLabel("Ready");
        gbc.gridx = 0; gbc.gridy = 2;
        p.add(lblStatus, gbc);

        lblClock = new JLabel();
        gbc.gridx = 0; gbc.gridy = 3;
        p.add(lblClock, gbc);

        return p;
    }

    private JPanel createResultsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 5, 2, 5);

        lblElapsed = createResultLabel(p, "Elapsed Time:", gbc, 0);
        lblSequential = createResultLabel(p, "Sequential Time:", gbc, 1);
        lblSpeedup = createResultLabel(p, "Speedup:", gbc, 2);
        lblEfficiency = createResultLabel(p, "Efficiency:", gbc, 3);
        lblProcessed = createResultLabel(p, "Processed:", gbc, 4);
        lblFailed = createResultLabel(p, "Failed:", gbc, 5);

        gbc.gridx = 0; gbc.gridy = 6; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        txtLogs = new JTextArea(5, 20);
        txtLogs.setEditable(false);
        p.add(new JScrollPane(txtLogs), gbc);

        gbc.gridy = 7; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        JLabel footer = new JLabel("Illustrative single run; report numbers come from the benchmark harness.");
        footer.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(footer, gbc);

        return p;
    }

    private JLabel createResultLabel(JPanel p, String text, GridBagConstraints gbc, int row) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        p.add(new JLabel(text), gbc);
        JLabel val = new JLabel("-");
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(val, gbc);
        return val;
    }

    private void browseFolder(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void updateSettingsEnablement() {
        boolean isSequential = "Sequential".equals(comboMode.getSelectedItem());
        spinnerThreads.setEnabled(!isSequential);
        checkCompare.setEnabled(!isSequential);
    }

    private void initClock() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        lblClock.setText(LocalTime.now().format(fmt));
        clockTimer = new Timer(500, e -> {
            lblClock.setText(LocalTime.now().format(fmt));
        });
        clockTimer.start();
    }

    public void updateStatus(String status) {
        lblStatus.setText(status);
    }

    public void updateProgress(int done, int total) {
        progressBar.setValue((int)(( (double)done / total) * 100));
        progressBar.setString(done + " / " + total);
    }

    public void resetProgress() {
        progressBar.setValue(0);
        progressBar.setString("0 / 0");
    }

    private void handleStart() {
        if (!validateInputs()) return;

        resetResults();
        setUIState(true);

        BatchRunner.Mode mode = BatchRunner.Mode.valueOf(
            comboMode.getSelectedItem().toString().toUpperCase().replace(" ", "_")
        );

        currentCancelToken = new CancelToken();
        currentWorker = new ProcessorWorker(
            this, runner, mode,
            Paths.get(txtInputFolder.getText().trim()),
            Paths.get(txtOutputFolder.getText().trim()),
            (Integer) spinnerThreads.getValue(),
            checkCompare.isSelected(),
            currentCancelToken
        );

        currentWorker.execute();
    }

    private void handleCancel() {
        if (currentCancelToken != null) {
            currentCancelToken.cancel();
        }
        lblStatus.setText("Cancelling...");
        btnStart.setEnabled(false);
        btnCancel.setEnabled(false);
    }

    private boolean validateInputs() {
        String inputStr = txtInputFolder.getText().trim();
        String outputStr = txtOutputFolder.getText().trim();

        if (inputStr.isEmpty()) {
            showError("Input folder cannot be empty");
            txtInputFolder.requestFocusInWindow();
            return false;
        }
        if (outputStr.isEmpty()) {
            showError("Output folder cannot be empty");
            txtOutputFolder.requestFocusInWindow();
            return false;
        }

        File inputDir = new File(inputStr);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            showError("Input folder does not exist or is not a directory");
            txtInputFolder.requestFocusInWindow();
            return false;
        }

        try {
            Path inputPath = Paths.get(inputStr).toAbsolutePath().normalize();
            Path outputPath = Paths.get(outputStr).toAbsolutePath().normalize();
            if (inputPath.equals(outputPath)) {
                showError("Input and output folders must be different");
                txtOutputFolder.requestFocusInWindow();
                return false;
            }
        } catch (Exception e) {
            showError("Invalid path format");
            return false;
        }

        return true;
    }

    public void showResults(Comparison comp) {
        RunResult run = comp.run();

        if (run.cancelled()) {
            lblElapsed.setText("-");
            lblSequential.setText("-");
            lblSpeedup.setText("-");
            lblEfficiency.setText("-");
            lblProcessed.setText("-");
            lblFailed.setText("-");
            txtLogs.append("Run was cancelled.\n");
        } else {
            lblElapsed.setText(String.format("%.2f s", run.elapsedNanos() / 1_000_000_000.0));
            lblSequential.setText(comp.baselineNanos() == null ? "-" : String.format("%.2f s", comp.baselineNanos() / 1_000_000_000.0));

            double s = comp.speedup();
            lblSpeedup.setText(Double.isNaN(s) ? "-" : String.format("%.2f", s));

            double e = comp.efficiency();
            lblEfficiency.setText(Double.isNaN(e) ? "-" : String.format("%.2f", e));

            lblProcessed.setText(String.valueOf(run.processed()));
            lblFailed.setText(String.valueOf(run.failedFiles().size()));

            if (comp.baselineWarning() != null) {
                txtLogs.append("Baseline Warning: " + comp.baselineWarning() + "\n");
            }
            for (String w : run.warnings()) {
                txtLogs.append("Warning: " + w + "\n");
            }
            for (FailedFile ff : run.failedFiles()) {
                txtLogs.append(ff.fileName() + ": " + ff.reason() + "\n");
            }
        }
    }

    private void resetResults() {
        lblElapsed.setText("-");
        lblSequential.setText("-");
        lblSpeedup.setText("-");
        lblEfficiency.setText("-");
        lblProcessed.setText("-");
        lblFailed.setText("-");
        txtLogs.setText("");
    }

    public void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public void setUIState(boolean running) {
        btnStart.setEnabled(!running);
        btnCancel.setEnabled(running);
        txtInputFolder.setEnabled(!running);
        txtOutputFolder.setEnabled(!running);
        comboMode.setEnabled(!running);
        spinnerThreads.setEnabled(!running && !"Sequential".equals(comboMode.getSelectedItem()));
        checkCompare.setEnabled(!running && !"Sequential".equals(comboMode.getSelectedItem()));

        if (!running) {
            progressBar.setValue(0);
            progressBar.setString("");
        }
    }
}
