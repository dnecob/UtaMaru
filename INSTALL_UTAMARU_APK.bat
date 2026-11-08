@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title UtaMaru - Install APK over USB
if not exist "utamaru-debug.apk" (
  echo ERROR: utamaru-debug.apk is missing.
  echo Run BUILD_UTAMARU_APK.bat first.
  goto :failed
)
set "ADB_EXE=adb.exe"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo Connect and unlock your phone with USB debugging enabled.
echo Installing without erasing app data...
"%ADB_EXE%" install -r "utamaru-debug.apk"
if errorlevel 1 (
  echo Installation failed. Possible causes: a different APK signature,
  echo a blocked USB permission, or a disconnected phone.
  goto :failed
)
echo SUCCESS: UtaMaru installed / updated.
echo Your saved presets are normally preserved by an in-place update.
pause
exit /b 0
:failed
pause
exit /b 1
