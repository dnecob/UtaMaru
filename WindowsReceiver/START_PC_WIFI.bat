@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0VoicePitchReceiver_Win64.exe" (
  echo ERROR: VoicePitchReceiver_Win64.exe is missing.
  pause
  exit /b 1
)
if not exist "%~dp0LOCALIZATION.ps1" (
  echo ERROR: LOCALIZATION.ps1 is missing in WindowsReceiver.
  pause
  exit /b 1
)
if not exist "%~dp0START_PC_WIFI.ps1" (
  echo ERROR: START_PC_WIFI.ps1 is missing in WindowsReceiver.
  pause
  exit /b 1
)
if not exist "%~dp0CHECK_VBCABLE.ps1" (
  echo ERROR: CHECK_VBCABLE.ps1 is missing in WindowsReceiver.
  pause
  exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0CHECK_VBCABLE.ps1"
if errorlevel 1 (
  echo.
  echo VB-CABLE setup check was cancelled or failed.
  pause
  exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0START_PC_WIFI.ps1"
set "EXITCODE=%ERRORLEVEL%"
echo.
pause
exit /b %EXITCODE%
