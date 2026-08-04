@echo off
call scripts\build.bat
if %ERRORLEVEL% neq 0 exit /b 1
rem Minimal GC tuning to return idle RSS to the OS. See docs/decision-log.md.
rem The previous fixed -Xmx/ParallelGCThreads/ConcGCThreads/CICompilerCount values
rem caused interactive stutter and were reverted; only the idle-only periodic GC
rem (JEP 346) remains, since it never fires during active use.
if not defined JAVA_OPTS set JAVA_OPTS=-XX:G1PeriodicGCInterval=60000
java %JAVA_OPTS% -cp build dev.javatexteditor.Main
