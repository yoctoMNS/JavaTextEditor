@echo off
rem セットアップスクリプト: OpenJDK 21 src.zip を lib\ に配置する
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set DEST=%SCRIPT_DIR%..\lib\src.zip

if exist "%DEST%" (
    echo Already exists: %DEST%
    exit /b 0
)

if not exist "%SCRIPT_DIR%..\lib\" mkdir "%SCRIPT_DIR%..\lib\"

echo === Setting up OpenJDK 21 source (src.zip) ===

rem 1. JAVA_HOME から探す
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\lib\src.zip" (
        echo Found: %JAVA_HOME%\lib\src.zip
        copy "%JAVA_HOME%\lib\src.zip" "%DEST%"
        echo Copied to: %DEST%
        exit /b 0
    )
)

rem 2. java コマンドのパスから JAVA_HOME を推定
for /f "delims=" %%i in ('where java 2^>nul') do (
    set JAVA_BIN=%%i
    goto :found_java
)
goto :try_known_paths

:found_java
rem java.exe -> bin\java.exe -> JDK root
for %%i in ("!JAVA_BIN!\..\..") do set JDK_ROOT=%%~fi
if exist "!JDK_ROOT!\lib\src.zip" (
    echo Found: !JDK_ROOT!\lib\src.zip
    copy "!JDK_ROOT!\lib\src.zip" "%DEST%"
    echo Copied to: %DEST%
    exit /b 0
)

:try_known_paths
rem 3. よく使われるインストール先を探す
set "SEARCH_DIRS=%ProgramFiles%\Java %ProgramFiles%\Eclipse Adoptium %ProgramFiles%\Microsoft %ProgramFiles%\Azul\Zulu21 %ProgramFiles%\BellSoft\LibericaJDK-21"
for %%D in (%SEARCH_DIRS%) do (
    if exist "%%D\" (
        for /d %%J in ("%%D\jdk-21*" "%%D\jdk21*") do (
            if exist "%%J\lib\src.zip" (
                echo Found: %%J\lib\src.zip
                copy "%%J\lib\src.zip" "%DEST%"
                echo Copied to: %DEST%
                exit /b 0
            )
        )
    )
)

rem 4. winget で Adoptium JDK をインストール試行
where winget >nul 2>&1
if %errorlevel% equ 0 (
    echo Installing OpenJDK 21 via winget...
    winget install EclipseAdoptium.Temurin.21.JDK
    rem インストール後に再スキャン
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
        if exist "%%J\lib\src.zip" (
            copy "%%J\lib\src.zip" "%DEST%"
            echo Copied to: %DEST%
            exit /b 0
        )
    )
)

echo.
echo ERROR: src.zip not found automatically.
echo Please place OpenJDK 21 src.zip manually:
echo   %DEST%
echo.
echo Download from: https://jdk.java.net/21/
echo   or install Eclipse Temurin 21 JDK from https://adoptium.net/
exit /b 1
