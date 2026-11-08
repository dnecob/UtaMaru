@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title UtaMaru - Build installable APK

echo ========================================================
echo  UtaMaru - build an installable DEBUG APK

echo ========================================================
if not exist "app\build.gradle.kts" (
  echo ERROR: Run this file from the UtaMaru project root.
  goto :failed
)

rem Use the JDK shipped with Android Studio if JAVA_HOME is not already set.
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
  set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
)
rem SDK discovery, without modifying build.gradle.kts or local.properties.
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk\platforms" (
  set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

if not exist "gradle\wrapper\gradle-wrapper.jar" (
  echo Gradle wrapper is missing. Running the official setup...
  if not exist "START_HERE.bat" (
    echo ERROR: START_HERE.bat is missing.
    goto :failed
  )
  call "START_HERE.bat"
  if errorlevel 1 goto :failed
)

echo.
echo [1/2] Compiling and signing debug APK with Gradle...
call "gradlew.bat" :app:assembleDebug --no-daemon
if errorlevel 1 goto :failed

if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo ERROR: Gradle completed but app-debug.apk was not found.
  goto :failed
)

echo.
echo [2/2] Copying finished APK into the project root...
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "utamaru-debug.apk" >nul
if errorlevel 1 goto :failed

echo.
echo SUCCESS: %CD%\utamaru-debug.apk
echo This APK is debug-signed by this computer, not a public-release APK.
echo To install via USB, run INSTALL_UTAMARU_APK.bat.
echo.
pause
exit /b 0

:failed
echo.
echo FAILED: APK was not produced. The Android Studio Build output
 echo or command-line error above contains the reason.
echo Check your JDK, Android SDK (API 36), and Internet connection.
echo.
pause
exit /b 1
