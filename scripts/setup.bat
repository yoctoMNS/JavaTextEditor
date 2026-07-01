@echo off
rem セットアップスクリプト: OpenJDK 21 の Java ソース(src.zip) と native C ソースを
rem git clone (sparse-checkout) 一本で lib\ に配置する。
rem git がインストール済みであることを前提とする。
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%..\lib
set SRC_ZIP=%LIB_DIR%\src.zip
set NATIVE_DIR=%LIB_DIR%\openjdk-native

if not exist "%LIB_DIR%\" mkdir "%LIB_DIR%\"

if exist "%SRC_ZIP%" if exist "%NATIVE_DIR%\" (
    echo src.zip and openjdk-native already exist. Nothing to do.
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

echo Configuring sparse-checkout for Java/native sources ...
git -C "%WORK_DIR%" sparse-checkout init --no-cone
git -C "%WORK_DIR%" sparse-checkout set "src/*/share/classes" "src/*/windows/classes" "src/*/share/native" "src/*/windows/native"

echo Checking out sources ...
git -C "%WORK_DIR%" checkout
if %errorlevel% neq 0 (
    echo ERROR: git checkout failed.
    rmdir /s /q "%WORK_DIR%"
    exit /b 1
)

rem ---- 1. src.zip の生成（module/pkg/Class.java 形式） ----

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

rem ---- 2. openjdk-native への native C ソース配置 ----

:setup_native
if exist "%NATIVE_DIR%\" (
    echo openjdk-native already exists: %NATIVE_DIR%
    goto :cleanup
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

:cleanup
rmdir /s /q "%WORK_DIR%"

echo.
echo === Setup complete ===
if exist "%SRC_ZIP%"    echo   src.zip    : %SRC_ZIP%
if exist "%NATIVE_DIR%" echo   native src : %NATIVE_DIR%
