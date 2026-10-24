# Project Brief: Parallel Batch Image Processor (Java)

## 1. Your role

You are my pair-programming partner and technical mentor on a university **Parallel Programming** assignment. I am a back-end developer and university student (Python/Django, .NET background, now required to use **Java** for this assignment).

Your job:
- Help me design, implement, test, benchmark, and document the project.
- Write clean, well-documented code, but **explain your design decisions** briefly so I understand and can defend the work. Engineering choices are highly valued; they must be justified, useful, and based on technical reasoning.
- Ask before making big architectural decisions. Small, obvious decisions: just make them and tell me.
- Keep me honest about correctness: if something might be a race condition or a flawed benchmark, say so.

The original assignment PDF is in Arabic. The **complete English translation is in Section 6** of this file. Treat Section 6 as the source of truth for requirements. Do not try to read the Arabic PDF.

## Project Status (2026-10-24)
### Environment
Windows, Java 21, IntelliJ IDEA Community. Use .\mvnw.cmd (mvn is not on PATH). pom has forkCount=0 as a workaround for a Surefire fork error; benchmarks run via plain `java` from run_benchmarks.cmd, never through Maven. Project path E:\HomeWork_1. CPU i5-10300H (4 physical / 8 logical cores). RAM unknown: ask before choosing -Xmx.
### Done
- Pipeline Gaussian Blur -> Sobel; Sequential, ParallelImage (ExecutorService + Semaphore), ParallelStrip (dedicated ForkJoinPool per run, barrier between stages).
- 25 tests pass: hand-computed cases, edge sizes, strip interface, 50x repetition tests for both parallel versions, fault-injection test for the checksum guard. Parallel outputs are pixel-identical to sequential for these tested paths (not for FULL-PIPELINED, see below).
- BenchmarkHarness: one JVM per mode via run_benchmarks.cmd, golden checksum file, position-dependent rolling checksum, COMPUTE_ONLY and FULL modes, per-phase timers, CSV export.
### Findings so far (sample runs, not final)
- COMPUTE_ONLY (10 images): ~2.7x at N=4, ~4x at N=16 on 4 physical cores; Karp-Flatt f is stable around 0.16-0.20.
- Strip defect: SobelEdgeFilter allocated a full-image grayscale buffer inside apply(), repeated per strip. Fixed by computing luminance on the fly.
- An old 2.8 s FULL-mode overhead in strip mode came from different decode/write code paths; fixed by unified decodeImage/writeImage.
- FULL mode was PHASED (decode ALL serially, compute ALL in parallel, write ALL serially). 48 images: sequential ~38.0 s, ParallelImage N=16 ~28.4 s (~1.34x); decode ~9.3 s and write ~15.4 s stay constant for every N. PHASED is kept as a labeled variant for the Amdahl discussion.
- A PIPELINED variant (each task decodes, computes and writes its own image; Semaphore bounds memory; strip mode uses reader/compute/writer pools with BlockingQueues) takes ~15 s vs ~31.5 s PHASED at N=4, but reports Correct=FALSE. Its timings are INVALID until fixed.
- GC unit bug (ms divided by 1e6) fixed; to be verified against -Xlog:gc*.
### Open items (in order)
1. Fix FULL-PIPELINED correctness: decide if it is a real data race or a harness verification bug (pixel-by-pixel diff of outputs); add a concurrency test using shared processor/filter instances from several threads (must fail first, then pass); no mutable per-call state in shared instances.
2. CSV header (real CPU name, physical/logical cores, OS, megapixels, seed, GC algorithm, build timestamp, heap, reader/writer/compute threads, permits), Rep/type/round columns, summary table (median, min, max, stddev, speedup vs true sequential, efficiency, Karp-Flatt f = (N - S)/(S*(N-1)), empty for N=1), stddev>5% warnings.
3. Final experiment: dataset sizes 16/32/48 images, >=10 repetitions for the sequential baseline, cool-down between configurations, resumable runs, realistic time estimate. Keep all raw CSV files.
4. UI (JavaFX, fall back to Swing), never freezes.
5. Technical report 4-6 pages (ask me which language).
### Rules
- Report only what you actually ran, with real output pasted ("written only" vs "executed and passed (N tests)"). Stop at the first failure.
- Correctness before speed; never tune toward a target time. Never include a Correct=FALSE row in any CSV used for the report; fail loudly.
- One dedicated pool per benchmark run, created before the timer and shut down in finally after it. Semaphore released in finally.
- Checksums in pipelined mode are keyed per image (file name), computed on the output buffer before writing.

## 2. Decisions already made

- **Path chosen:** Path 1 (Parallel Algorithm and Practical Application).
- **Domain:** Image and media processing.
- **Algorithm:** Gaussian Blur followed by Sobel edge detection (Canny may be added later as an optional extra). Both are 2D convolutions, so they are almost perfectly data-parallel.
- **Application:** a **Batch Image Processor** desktop app that applies the filter pipeline to hundreds of high-resolution images at once.
- **Language:** Java (mandatory). UI: JavaFX or Swing (propose which, then confirm with me).
- **Two levels of parallelism to implement and compare:**
  1. Distribute **different images** across a thread pool (`ExecutorService`).
  2. Split a **single image** into horizontal strips/tiles processed in parallel (`ForkJoinPool` or a pool of worker threads).
- Every algorithm must exist in **two versions**: single-threaded (sequential baseline) and multi-threaded.

## 3. What to build

1. **Sequential version** of the pipeline (baseline, must be correct first).
2. **Parallel version(s)** with proper thread management, partitioning, and synchronization.
3. **Desktop app** with a **responsive UI that never freezes** during heavy processing (use `SwingWorker` or `javafx.concurrent.Task`; keep thread logic separated from UI code). It should let the user pick an input folder, choose the mode (sequential / parallel images / parallel strips), choose the number of threads, show a progress bar, show the execution time of each mode, and display the computed speedup (`Speedup = T_sequential / T_parallel`).
4. **Benchmark harness** (can be a separate CLI class) that runs all modes with thread counts **N = 1, 2, 4, 8, 16** and several data sizes, and exports results to CSV for graphing.
5. **Correctness test:** the parallel output must be **pixel-identical** to the sequential output for every image (this is also evidence of no race conditions).
6. **Short technical report** (4-6 pages), see deliverables below. Help me draft it from the real measurements.

## 4. Things to pay attention to

**Concurrency correctness (30% of grade)**
- No race conditions, no deadlocks. Prefer designs where threads write to **disjoint regions** of an output array, so little locking is needed.
- Use proper tools and document why each is used: `ExecutorService`/thread pools, `AtomicInteger` or `LongAdder` for the progress counter, `ConcurrentHashMap` or `ReentrantLock` for shared stats/results, `CountDownLatch` or `Future`s to wait for completion. Shut down executors properly.
- Handle exceptions inside worker tasks (a failed image must not hang the app).
- Strips need correct **boundary handling**: read from the immutable *input* array (including neighbor rows), write only to the thread's own rows of the *output* array.

**Real speedup (30% of grade)**
- Do **not** use `BufferedImage.getRGB/setRGB` per pixel in the hot loop (very slow). Decode the image once into an `int[]`/`byte[]` (preferably grayscale or separate channels) and operate on arrays directly.
- Avoid false sharing and unnecessary allocation inside the inner loops; reuse buffers where reasonable.
- Use enough work: e.g. 200+ high-resolution images (4K-ish) so differences are visible.
- Image file reading/decoding and writing are the **serial fraction** (Amdahl's Law). Consider reading images ahead of time or measuring **compute time separately from total time (including I/O)**, and report both.

**Benchmark quality (20% of grade)**
- JVM **warm-up** is mandatory: do several warm-up runs before measuring (JIT effects).
- Use `System.nanoTime()`, run at least 5 repetitions, report the **median** (and ideally min/max).
- Vary both N (thread count) and data size. Record the machine's CPU model and number of physical/logical cores, since speedup is capped by them.
- Be ready to explain **bottlenecks** (disk I/O, memory bandwidth, thread overhead, serial fraction) and discuss **Amdahl's Law** with the real numbers.

**Application quality (20% of grade)**
- UI must stay responsive during processing. Business/threading logic separated from UI code (e.g. packages like `core`, `parallel`, `ui`, `benchmark`).
- Code must be well organized and documented (Javadoc/comments, README with build/run instructions).

**General**
- Use Maven or Gradle. Java 17+ unless I say otherwise.
- Work step by step: first project structure and plan, then sequential version and correctness tests, then parallel versions, then benchmarks, then UI, then report. Check in with me after each milestone.
- The report language is not specified by the assignment; ask me which language to use before drafting it.

## 5. Deliverables (from the assignment)

1. Source code, well organized and clearly documented, with thread logic separated from the application UI.
2. Short technical report (4-6 pages) containing:
   - Explanation of the solution architecture and how tasks are divided among threads.
   - Synchronization mechanisms used (Locks, Semaphores, Atomic Variables, Thread Pools).
   - A table and a benchmark graph showing sequential vs. parallel execution time as the number of threads changes (N = 1, 2, 4, 8, 16) and as the data size changes, with interpretation of the results and a discussion of the effect of Amdahl's Law.

## 6. Full English translation of the assignment

### Parallel Programming
**Subject:** Using Threads to build parallel algorithms and applications, *or* a semantic search engine.

### Introduction and goals
This assignment aims to move the theoretical concepts of parallel programming and multithreading into tangible, practical application. The student must choose and implement **one of the two available paths** (Section 1 or Section 2): either build a parallel algorithm and employ it in a practical application, or build a smart multimedia search engine using semantic embeddings.

### Section 1: Parallel algorithm and practical application (Path 1)
If the student chooses this path, they must complete the following three stages:

1. **Thread management:** Start from the concept of threads (creating threads, partitioning data or tasks, synchronization, and avoiding race conditions and deadlocks).
2. **Building the parallel algorithm:** Choose a computational algorithm that can be parallelized and implement it in two versions: a traditional **single-threaded** (sequential) version and a **multi-threaded** (parallel) version.
3. **Employing it in a practical application:** Integrate the algorithm into a complete application (desktop, web, or mobile) that solves a clear problem, and display the execution time in both cases in order to compute the speedup factor: **Speedup = T_sequential / T_parallel**.

**Suggested algorithms and applications for this path:**

| Domain | Suggested parallel algorithm | Suitable practical application | How to divide the work among threads |
|---|---|---|---|
| Image and media processing | 2D convolution algorithms, edge detection (Canny/Sobel), or Fourier transform | **Batch Image Processor:** an application that applies complex filters or noise removal to hundreds of high-resolution images at once | Split a single image into horizontal strips/tiles (data parallelism), or distribute different images over a group of threads (Thread Pool) |
| Sorting and searching big data | Parallel Merge Sort, Parallel QuickSort, or a mini MapReduce | **Server Log File Analyzer:** a tool that analyzes and sorts huge log files (several GB) to extract critical errors and visit statistics | Split the huge file into chunks; each thread processes a chunk independently, then the partial results are merged into a synchronized data structure |
| Searching in data and graphs | Parallel Breadth-First Search (Parallel BFS), or parallel shortest-path algorithms (Parallel Dijkstra / A*) | **Multi-threaded File Crawler:** an application that indexes the entire hard disk, or finds the shortest path in a huge road network, or a local navigation system | Use a concurrent queue (Concurrent Queue) from which threads pull unexplored nodes/folders and to which they add newly discovered paths |
| Simulation and cybersecurity | Parallel Monte Carlo simulation, or Parallel Hashing | Financial risk evaluator or **password auditor:** a tool that tests password strength by generating and comparing millions of SHA-256 hashes per second | Distribute the probability range or sample space equally over the number of threads available on the processor |

### Section 2: Smart multimodal search engine (Path 2) — NOT chosen
*(Included for completeness only. We are not doing this path.)*

**Alternative project title:** Develop a local search engine over thousands of images and video clips using semantic embeddings.

**Application description:** Instead of Path 1, the student may choose to build an application that lets the user search thousands of local images and videos using a natural description in Arabic (typed or spoken).

Example queries: "photo of the payment receipt at the pharmacy", "video of the cat jumping", "screenshot containing a discount code".

Required technical structure:
1. **Embedding and feature extraction:** Use a modern multilingual/multimodal embedding model (such as EmbeddingGemma 2, or a pipeline combining video frame extraction / speech-to-text with embedding), converting images, selected video keyframes, and Arabic text into vectors in a shared semantic space.
2. **Phase 1 (Indexing):** Compare the indexing time of 1,000+ media files processed one by one (sequentially) versus distributing frame extraction and vector computation over several threads (in parallel), while making sure the UI does not freeze.
3. **Phase 2 (Search and similarity computation):** When the user enters a text or voice query, convert it to a vector, then compute the cosine similarity (Cosine Similarity = (A · B) / (||A|| ||B||)) between the query vector and the thousands of stored vectors in two ways: (a) sequential search: a single loop over all vectors computing similarity and ranking results; (b) parallel search: split the huge vector matrix over several worker threads to compute similarity in parallel, then merge the top-K results (Top-K Reduction).

### Required outputs and evaluation criteria

**Deliverables:**
1. **Source code:** well organized and clearly documented, with the thread logic separated from the application UI.
2. **Short technical report (4-6 pages)** including: an explanation of the solution architecture and how tasks are divided among threads; the synchronization mechanisms used (Locks, Semaphores, Atomic Variables, Thread Pools); a table and a benchmark graph showing sequential vs. parallel execution time as the number of threads changes (N = 1, 2, 4, 8, 16) and as the data size changes, with interpretation of the results and a discussion of the effect of Amdahl's Law.

**Suggested grading distribution:**

| Criterion | Weight | Description and details |
|---|---|---|
| Thread management and synchronization safety | 30% | Code free of race conditions and proper use of concurrency tools |
| Efficiency of the parallel algorithm and achieved speedup | 30% | Achieving a real, tangible speedup compared to the sequential version |
| Practical application and user interface quality | 20% | Employing the algorithm in a useful context and the application UI not freezing during heavy processing |
| Accuracy of timing measurements, graphs, and report analysis | 20% | Experimental performance analysis and interpretation of bottlenecks |

## 7. Current task: fix the FULL-PIPELINED correctness bug (see Open items, item 1), nothing else until that is done and I approve.
