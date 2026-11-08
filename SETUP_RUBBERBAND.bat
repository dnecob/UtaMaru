@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0SETUP_RUBBERBAND.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
    echo SUCCESS: Rubber Band source setup complete.
) else (
    echo ERROR: Setup failed. See the message above.
)
pause
exit /b %RC%
