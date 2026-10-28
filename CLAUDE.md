# Project Brief: Parallel Batch Image Processor (Java), UI phase

## Role
You are my pair-programming partner on a university Parallel Programming assignment (Path 1). Explain design decisions briefly, because I must defend them orally. Ask before big architectural decisions. Tell me honestly about races, flawed measurements, or anything that looks wrong.

The full assignment translation and the project history (benchmark phase) are in `docs/CLAUDE_OLD.md`. Read it only when you need the assignment text.

## What the project is
A Batch Image Processor in Java 21 (Maven, IntelliJ). Pipeline: Gaussian Blur (3x3) then Sobel edge detection. Three implementations, each with a sequential and a parallel version:
- Sequential (baseline)
- ParallelImage: different images over a fixed thread pool, Semaphore bounds images in memory
- ParallelStrip: one image split into strips, dedicated ForkJoinPool, barrier between the two filters

Grading: 30% thread safety and synchronization, 30% real speedup, 20% practical app with a UI that never freezes, 20% timing accuracy, graphs and report.

## Status
- DONE and FROZEN: core filters, the three processors, 45+ tests (pixel-identical to sequential, shared-instance concurrency test), BenchmarkHarness (one JVM per mode, golden checksums, unique temp dirs, process guard, CSV with header). FULL_PIPELINED exists for ParallelImage only and was verified (50/50 correct runs, smoke test OK).
- Do NOT change benchmark behavior. If the UI needs code that currently lives inside BenchmarkHarness, ask me first; any extraction must keep all tests green and the smoke test unchanged.
- Old `results_*.csv` files from the concurrent-runs period are invalid and live in `old_invalid_results/`.

## Current phase: Windows 11 desktop app (Swing)
- Swing, JDK only (remove the org.openjfx dependency/plugin from the pom). System look and feel.
- Small on purpose: one window, 3-4 classes. Thread logic stays outside the `ui` package; the UI only calls existing processors through a small non-UI service class.
- Never freezes: all processing in a SwingWorker, a live clock label (javax.swing.Timer) proves it. Cancel must work.
- The UI shows one illustrative run. The benchmark harness remains the source of all numbers in the report.

## After the UI (not before my approval)
1. Final experiment with the harness (machine left alone): 16 and 48 images, N = 1, 2, 4, 8, 16, 5 repetitions (10 for sequential), same `-Xmx6G` for every mode.
2. Charts from the CSV files and the 4-6 page technical report (ask me which language).

## Environment
Windows 11, Java 21, IntelliJ Community. 4 physical / 8 logical cores (i5-10300H), 15.84 GB RAM.
- Your shell cannot run mvnw.cmd, jps or taskkill reliably: do not fight them. I build in IntelliJ (Build -> Rebuild Project) and tell you when it is done. Run compiled classes with `java -cp "target/classes;target/test-classes" ...`.
- Never start a benchmark while another is running. Check processes with PowerShell (`Get-CimInstance Win32_Process`); if one exists, tell me and I will kill it.

## Rules
- Real output only, never simulated. Write "NOT RUN" for anything you did not run. Report "written only" vs "executed and passed (N tests)".
- Stop at the first failure and tell me. Work in small steps and check in after each.
- No new features beyond what I ask. The project is already complex enough.
- Correctness before speed. Never include a row with a failed correctness check in report data.
- Concurrency rules: no mutable per-call state in shared processors or filters; one dedicated pool per run, created before the timer and shut down in `finally`; Semaphore always released in `finally`.