@echo off
setlocal enabledelayedexpansion
call scripts\build.bat
if %ERRORLEVEL% neq 0 exit /b 1

dir /s /b test\*.java > build\test-sources.txt
javac -encoding UTF-8 -cp build -d build @build\test-sources.txt
if %ERRORLEVEL% neq 0 (
    echo Test compilation FAILED
    exit /b 1
)

set OVERALL_PASS=0
set OVERALL_FAIL=0

for /r build %%f in (*Test.class) do (
    set "fname=%%~nf"
    rem exclude nested classes whose name contains "$" (*$*.class)
    if "!fname:$=!" == "!fname!" (
        set "classfile=%%f"
        set "classfile=!classfile:%cd%\build\=!"
        set "classfile=!classfile:\=.!"
        set "classfile=!classfile:.class=!"
        echo === !classfile! ===
        java -cp build !classfile!
        if !ERRORLEVEL! equ 0 (
            set /a OVERALL_PASS+=1
        ) else (
            set /a OVERALL_FAIL+=1
        )
    )
)

echo.
echo === Summary: %OVERALL_PASS% class(es) passed, %OVERALL_FAIL% class(es) failed ===
if %OVERALL_FAIL% gtr 0 exit /b 1
endlocal
