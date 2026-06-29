@echo off
rem セットアップスクリプト: OpenJDK 21 src.zip と native C ソースを lib\ に配置する
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%..\lib
set SRC_ZIP=%LIB_DIR%\src.zip
set NATIVE_DIR=%LIB_DIR%\openjdk-native

if not exist "%LIB_DIR%\" mkdir "%LIB_DIR%\"

rem ---- 1. src.zip の配置 ----

if exist "%SRC_ZIP%" (
    echo src.zip already exists: %SRC_ZIP%
    goto :setup_native
)

echo === Setting up OpenJDK 21 source (src.zip) ===

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\lib\src.zip" (
        copy "%JAVA_HOME%\lib\src.zip" "%SRC_ZIP%"
        echo Copied src.zip to: %SRC_ZIP%
        goto :setup_native
    )
)

for /f "delims=" %%i in ('where java 2^>nul') do (
    set JAVA_BIN=%%i
    goto :found_java
)
goto :try_known_paths

:found_java
for %%i in ("!JAVA_BIN!\..\..") do set JDK_ROOT=%%~fi
if exist "!JDK_ROOT!\lib\src.zip" (
    copy "!JDK_ROOT!\lib\src.zip" "%SRC_ZIP%"
    echo Copied src.zip to: %SRC_ZIP%
    goto :setup_native
)

:try_known_paths
set "SEARCH_DIRS=%ProgramFiles%\Java %ProgramFiles%\Eclipse Adoptium %ProgramFiles%\Microsoft %ProgramFiles%\Azul\Zulu21 %ProgramFiles%\BellSoft\LibericaJDK-21"
for %%D in (%SEARCH_DIRS%) do (
    if exist "%%D\" (
        for /d %%J in ("%%D\jdk-21*" "%%D\jdk21*") do (
            if exist "%%J\lib\src.zip" (
                copy "%%J\lib\src.zip" "%SRC_ZIP%"
                echo Copied src.zip to: %SRC_ZIP%
                goto :setup_native
            )
        )
    )
)

where winget >nul 2>&1
if %errorlevel% equ 0 (
    echo Installing OpenJDK 21 via winget...
    winget install EclipseAdoptium.Temurin.21.JDK
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
        if exist "%%J\lib\src.zip" (
            copy "%%J\lib\src.zip" "%SRC_ZIP%"
            echo Copied src.zip to: %SRC_ZIP%
            goto :setup_native
        )
    )
)

echo WARNING: src.zip not found. Place it manually at: %SRC_ZIP%

rem ---- 2. OpenJDK native C ソースの取得（sparse-checkout） ----

:setup_native
if exist "%NATIVE_DIR%\" (
    echo openjdk-native already exists: %NATIVE_DIR%
    goto :done
)

echo === Fetching OpenJDK 21 native C sources (sparse-checkout) ===

where git >nul 2>&1
if %errorlevel% neq 0 (
    echo WARNING: git not found. Skipping native C source download.
    echo   Install git and re-run setup.bat to enable native method tracing.
    goto :done
)

set WORK_DIR=%LIB_DIR%\_openjdk_clone_tmp
if exist "%WORK_DIR%" rmdir /s /q "%WORK_DIR%"
mkdir "%WORK_DIR%"

echo Cloning openjdk/jdk (blob:none, depth=1) ...
git clone --no-checkout --depth=1 --filter=blob:none --branch jdk-21+35 https://github.com/openjdk/jdk.git "%WORK_DIR%"
if %errorlevel% neq 0 (
    echo ERROR: git clone failed.
    rmdir /s /q "%WORK_DIR%"
    goto :done
)

echo Configuring sparse-checkout for native sources ...
git -C "%WORK_DIR%" sparse-checkout init --cone
git -C "%WORK_DIR%" sparse-checkout set "src/java.base/share/native" "src/java.base/windows/native" "src/java.desktop/share/native" "src/java.desktop/windows/native" "src/java.lang.instrument/share/native" "src/jdk.management/share/native"

echo Checking out native sources ...
git -C "%WORK_DIR%" checkout
if %errorlevel% neq 0 (
    echo ERROR: git checkout failed.
    rmdir /s /q "%WORK_DIR%"
    goto :done
)

move "%WORK_DIR%\src" "%NATIVE_DIR%"
rmdir /s /q "%WORK_DIR%"
echo Native C sources stored at: %NATIVE_DIR%

:done
echo.
echo === Setup complete ===
if exist "%SRC_ZIP%"    echo   src.zip    : %SRC_ZIP%
if exist "%NATIVE_DIR%" echo   native src : %NATIVE_DIR%
