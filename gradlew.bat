@echo off
rem Windows: run "gradle wrapper --gradle-version 8.11.1" once (needs Gradle installed), then use the generated wrapper.
where gradle >nul 2>nul && (gradle %* & exit /b %errorlevel%)
echo Gradle not found. Install Gradle 8.11.1 and run: gradle wrapper --gradle-version 8.11.1
exit /b 1
