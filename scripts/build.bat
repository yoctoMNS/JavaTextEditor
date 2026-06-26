@echo off
setlocal
if not exist build mkdir build
dir /s /b src\*.java > build\sources.txt
javac -encoding UTF-8 -d build @build\sources.txt
if %ERRORLEVEL% neq 0 (
    echo Build FAILED
    exit /b 1
)
echo Build OK
endlocal
