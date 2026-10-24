@echo off
SET JAVA_OPTS=-Xmx8G -XX:+UseG1GC
SET DATASET_DIR=./dataset
SET OUT_CSV=results.csv

echo [1/4] Generating Dataset...
java %JAVA_OPTS% -cp target\classes com.homework.processor.benchmark.DatasetGenerator 25 3840 2160 %DATASET_DIR% 42

echo [2/4] Running Sequential Baseline...
java %JAVA_OPTS% -cp target\classes com.homework.processor.benchmark.BenchmarkHarness --dataset %DATASET_DIR% --out %OUT_CSV% --mode COMPUTE_ONLY

echo [3/4] Running Parallel-Image...
java %JAVA_OPTS% -cp target\classes com.homework.processor.benchmark.BenchmarkHarness --dataset %DATASET_DIR% --out %OUT_CSV% --mode COMPUTE_ONLY

echo [4/4] Running Parallel-Strip...
java %JAVA_OPTS% -cp target\classes com.homework.processor.benchmark.BenchmarkHarness --dataset %DATASET_DIR% --out %OUT_CSV% --mode COMPUTE_ONLY

echo.
echo Benchmarks complete. Results saved to %OUT_CSV%.
pause
