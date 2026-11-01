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

## Project Status (Verified by User)
- **UI**: Finished and wired (ImageProcessorUI + ProcessorWorker + BatchRunner). Status line ends with "Done", "Cancelled", or "Failed: <cause>".
- **Acceptance**: Verified with 48 4K images (3840x2160, seed 42).
    - Window stays responsive. All modes finish.
    - Parallel outputs are byte-identical (SHA256) to Sequential.
    - Cancel returns to idle in ~1s; no stray baseline temp folders, 0-byte files, or BatchRunner threads remain.
    -  empty folder shows a message; a corrupt file is reported in the failed list and the run continues; input == output is rejected.
    - Output folder inside input folder is accepted.
    - Closing window during run ends process immediately.
- **Sample Performance**: Sequential 35s; Parallel Images (4 threads) 11.8s (speedup 3.0, efficiency 0.76); Parallel Strips (4 threads) 27.65s (speedup 1.3, efficiency 0.33).
- **Packaging**: Runnable JAR `target\parallel-image-processor-1.0-SNAPSHOT.jar` (Java 17 release). `run_app.cmd` tested via double-click and paths with spaces. README.md written.
- **Tests**: Last run in IntelliJ: 73 tests, 0 failures.

## Open Items
1. `run_benchmarks.cmd` is a smoke script: runs `BenchmarkHarness` 3 times with identical arguments (25 images, -Xmx8G, --out results.csv), producing `results.csv_<timestamp>.csv`.
2. `BenchmarkHarness` runs all implementations (Sequential, ParallelImage, ParallelStrip) inside ONE JVM per invocation; no option to select a single implementation.
3. Strip mode in harness still uses `processFixed` (new ForkJoinPool per image).
4. Final experiment, charts, and 4-6 page technical report are not done.
5. Stray files in repo root: `crash_stderr.log`, `crash_test.csv_*.csv`, and four `sample_*.csv`.

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
