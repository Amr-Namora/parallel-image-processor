@echo off
if not exist "%~dp0target\parallel-image-processor-1.0-SNAPSHOT.jar" (
    echo The application JAR was not found.
    echo Please run 'package' in the IntelliJ Maven tool window first.
    pause
    exit /b
)
start javaw -Xmx4G -jar "%~dp0target\parallel-image-processor-1.0-SNAPSHOT.jar"
