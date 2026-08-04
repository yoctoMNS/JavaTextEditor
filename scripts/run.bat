@echo off
call scripts\build.bat
if %ERRORLEVEL% neq 0 exit /b 1
rem Memory/GC tuning to keep idle RSS and thread count low on a lightweight desktop
rem editor. See docs/decision-log.md for the rationale. Does not override an
rem already-set JAVA_OPTS.
if not defined JAVA_OPTS set JAVA_OPTS=-Xmx1536m -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1 -XX:CICompilerCount=2 -XX:G1PeriodicGCInterval=60000
java %JAVA_OPTS% -cp build dev.javatexteditor.Main
