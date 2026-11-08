@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0VoicePitchReceiver_Win64.exe" (
  echo ERROR: VoicePitchReceiver_Win64.exe is missing.
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
echo =======================================================
echo   UtaMaru Windows PC Microphone - Wi-Fi mode
echo =======================================================
echo.
echo Use only a trusted private network.
echo VB-CABLE official download: https://vb-audio.com/Cable/
echo To copy a URL or open the official website, run COPY_OFFICIAL_URLS.bat.
echo The phone and the PC must be able to communicate on the same LAN.
echo.
echo PC IPv4 addresses (select the address on your local network):
ipconfig | findstr /i "IPv4"
echo.
echo In UtaMaru, enter the PC IPv4 address as the PC host.
echo Tap "Send pitch-converted microphone audio to PC"
echo or "Send raw microphone audio to PC" in the UtaMaru app.
echo.
echo Receiver playback device: CABLE Input
echo Discord / OBS / games microphone INPUT: CABLE Output
echo.
echo Allow this receiver through Windows Firewall on PRIVATE networks only.
echo NEVER expose TCP port 50005 to the public Internet.
echo.
"%~dp0VoicePitchReceiver_Win64.exe" -lan
set "EXITCODE=%ERRORLEVEL%"
echo.
echo WindowsReceiver stopped.
pause
exit /b %EXITCODE%
