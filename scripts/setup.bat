@echo off
rem Setup script: fetches OpenJDK 21 Java sources, i.e. src.zip, and native
rem C/C++ sources via a single git clone with sparse-checkout into lib\.
rem Requires git to be installed.
rem NOTE 1: keep this file ASCII-only. cmd.exe parses batch files using the
rem active console codepage, and this repo's default UTF-8 encoding does not
rem match the legacy codepage many Windows consoles use, e.g. CP932 on
rem Japanese Windows. Multi-byte UTF-8 sequences can be misread as batch
rem metacharacters under the wrong codepage and break parsing, observed as
rem garbled text plus "Exited with code 255". Put any commentary in
rem Japanese/other non-ASCII text in SKILL.md instead of this file.
rem NOTE 2: avoid literal parentheses in echo/rem text that sits inside an
rem if/for block below. cmd.exe's block parser scans raw characters for
rem "(" and ")" to find the block's end; an unescaped pair inside such text
rem can desync that scan and corrupt execution (observed as a stray word
rem from the text being run as its own command). This is safe at top level
rem (outside any block), which is why this header comment may still use them.
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%..\lib
set SRC_ZIP=%LIB_DIR%\src.zip
set NATIVE_DIR=%LIB_DIR%\openjdk-native
set HOTSPOT_DIR=%NATIVE_DIR%\hotspot

if not exist "%LIB_DIR%\" mkdir "%LIB_DIR%\"

set HOTSPOT_READY=0
if exist "%HOTSPOT_DIR%\" (
    dir /s /b "%HOTSPOT_DIR%\*.cpp" >nul 2>&1
    if !errorlevel! equ 0 set HOTSPOT_READY=1
)

if exist "%SRC_ZIP%" if exist "%NATIVE_DIR%\" if !HOTSPOT_READY! equ 1 (
    echo src.zip, openjdk-native, and hotspot sources already exist. Nothing to do.
    goto :eof
)

where git >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: git not found. This script requires git to fetch OpenJDK 21 sources.
    exit /b 1
)

set WORK_DIR=%LIB_DIR%\_openjdk_clone_tmp
if exist "%WORK_DIR%" rmdir /s /q "%WORK_DIR%"
mkdir "%WORK_DIR%"

echo === Cloning openjdk/jdk (blob:none, depth=1) ===
git clone --no-checkout --depth=1 --filter=blob:none --branch jdk-21+35 https://github.com/openjdk/jdk.git "%WORK_DIR%"
if %errorlevel% neq 0 (
    echo ERROR: git clone failed.
    rmdir /s /q "%WORK_DIR%"
    exit /b 1
)

rem HotSpot's directory tree is deeply nested and can exceed Windows' 260
rem character MAX_PATH limit, especially under an already-long base path.
rem Enable long path support for this clone to avoid checkout/copy failures.
git -C "%WORK_DIR%" config core.longpaths true

echo Configuring sparse-checkout for Java/native sources ...
git -C "%WORK_DIR%" sparse-checkout init --no-cone
git -C "%WORK_DIR%" sparse-checkout set "src/*/share/classes" "src/*/windows/classes" "src/*/share/native" "src/*/windows/native" "src/hotspot/share"

echo Checking out sources ...
git -C "%WORK_DIR%" checkout
if %errorlevel% neq 0 (
    echo ERROR: git checkout failed.
    rmdir /s /q "%WORK_DIR%"
    exit /b 1
)

rem ---- 1. Build src.zip (module/pkg/Class.java layout) ----

if exist "%SRC_ZIP%" (
    echo src.zip already exists: %SRC_ZIP%
    goto :setup_native
)

echo === Building src.zip from checked-out Java sources ===
set ZIP_STAGE=%LIB_DIR%\_src_zip_stage_tmp
if exist "%ZIP_STAGE%" rmdir /s /q "%ZIP_STAGE%"
mkdir "%ZIP_STAGE%"

for /d %%M in ("%WORK_DIR%\src\*") do (
    set MODULE_NAME=%%~nxM
    if exist "%%M\share\classes\" (
        mkdir "%ZIP_STAGE%\!MODULE_NAME!" 2>nul
        xcopy "%%M\share\classes\*" "%ZIP_STAGE%\!MODULE_NAME!\" /e /i /q /y >nul
    )
    if exist "%%M\windows\classes\" (
        mkdir "%ZIP_STAGE%\!MODULE_NAME!" 2>nul
        xcopy "%%M\windows\classes\*" "%ZIP_STAGE%\!MODULE_NAME!\" /e /i /q /y >nul
    )
)

dir /s /b "%ZIP_STAGE%\*.java" >nul 2>&1
if %errorlevel% equ 0 (
    pushd "%ZIP_STAGE%"
    jar cf "%SRC_ZIP%" -C "%ZIP_STAGE%" .
    popd
    echo Created src.zip: %SRC_ZIP%
) else (
    echo WARNING: No Java sources found; src.zip was not created.
)
rmdir /s /q "%ZIP_STAGE%"

rem ---- 2. Place native C sources under openjdk-native ----

:setup_native
if exist "%NATIVE_DIR%\" (
    echo openjdk-native already exists: %NATIVE_DIR%
    goto :setup_hotspot
)

echo === Placing native C sources ===
mkdir "%NATIVE_DIR%"
for /d %%M in ("%WORK_DIR%\src\*") do (
    set MODULE_NAME=%%~nxM
    if exist "%%M\share\native\" (
        mkdir "%NATIVE_DIR%\!MODULE_NAME!\share" 2>nul
        xcopy "%%M\share\native" "%NATIVE_DIR%\!MODULE_NAME!\share\native\" /e /i /q /y >nul
    )
    if exist "%%M\windows\native\" (
        mkdir "%NATIVE_DIR%\!MODULE_NAME!\windows" 2>nul
        xcopy "%%M\windows\native" "%NATIVE_DIR%\!MODULE_NAME!\windows\native\" /e /i /q /y >nul
    )
)
echo Native C sources stored at: %NATIVE_DIR%

rem ---- 3. Place HotSpot (share) sources ----
rem src\hotspot has no subdirectory named "native", so the loop above never
rem picks it up. Runtime functions such as JVM_GC() live here. The os/cpu
rem specific backends (src\hotspot\os\*, src\hotspot\cpu\*) are excluded for
rem now since they are large and platform specific; only the common "share"
rem sources are fetched.

:setup_hotspot
if !HOTSPOT_READY! equ 1 (
    echo hotspot sources already exist: %HOTSPOT_DIR%
    goto :cleanup
)
if exist "%WORK_DIR%\src\hotspot\share\" (
    echo === Placing HotSpot share sources ===
    mkdir "%HOTSPOT_DIR%\share" 2>nul
    xcopy "%WORK_DIR%\src\hotspot\share" "%HOTSPOT_DIR%\share\" /e /i /q /y >nul
    echo HotSpot sources stored at: %HOTSPOT_DIR%\share
) else (
    echo WARNING: src/hotspot/share not found in checkout; hotspot sources were not placed.
)

:cleanup
rmdir /s /q "%WORK_DIR%"

echo.
echo === Setup complete ===
if exist "%SRC_ZIP%"    echo   src.zip    : %SRC_ZIP%
if exist "%NATIVE_DIR%" echo   native src : %NATIVE_DIR%
if exist "%HOTSPOT_DIR%" echo   hotspot src: %HOTSPOT_DIR%
