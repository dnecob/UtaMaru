@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "VBCABLE_URL=https://vb-audio.com/Cable/"
set "ADB_URL=https://developer.android.com/tools/releases/platform-tools?hl=ja"

:MAIN_MENU
cls
echo =====================================================
echo  UtaMaru - Official download links
echo =====================================================
echo.
echo  [1] VB-CABLE (VB-Audio)
echo      %VBCABLE_URL%
echo.
echo  [2] ADB (Google SDK Platform-Tools)
echo      %ADB_URL%
echo.
echo  [0] Exit
echo.
set "ANSWER="
set /p "ANSWER=Select: "
if "%ANSWER%"=="1" goto VB_MENU
if "%ANSWER%"=="2" goto ADB_MENU
if "%ANSWER%"=="0" exit /b 0
echo Invalid selection.
pause
goto MAIN_MENU

:VB_MENU
set "ACTIVE_NAME=VB-CABLE (VB-Audio)"
set "ACTIVE_URL=%VBCABLE_URL%"
goto ACTION_MENU

:ADB_MENU
set "ACTIVE_NAME=ADB (Google SDK Platform-Tools)"
set "ACTIVE_URL=%ADB_URL%"
goto ACTION_MENU

:ACTION_MENU
cls
echo =====================================================
echo  %ACTIVE_NAME%
echo =====================================================
echo  %ACTIVE_URL%
echo.
echo  [1] Copy the URL
echo  [2] Open this URL in browser
echo  [0] Back to website selection
echo.
set "ANSWER="
set /p "ANSWER=Select: "
if "%ANSWER%"=="1" goto COPY_URL
if "%ANSWER%"=="2" goto OPEN_URL
if "%ANSWER%"=="0" goto MAIN_MENU
echo Invalid selection.
pause
goto ACTION_MENU

:COPY_URL
powershell.exe -NoLogo -NoProfile -Command "Set-Clipboard -Value $env:ACTIVE_URL" 2>nul
if errorlevel 1 (echo Failed to copy URL. Copy the displayed URL manually.) else (echo URL copied to clipboard.)
pause
goto ACTION_MENU

:OPEN_URL
start "" "%ACTIVE_URL%"
if errorlevel 1 (echo Failed to open website. Copy the displayed URL manually.) else (echo Requested website opened.)
pause
goto ACTION_MENU
