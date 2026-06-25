@echo off
call scripts\build.bat
if %ERRORLEVEL% neq 0 exit /b 1
java -cp build dev.vimacs.Main
