@echo off
setlocal
cd /d "%~dp0"
echo [VoicePitchLooper] Preparing official Gradle wrapper...
echo Checking the official SHA-256 checksum...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $file='gradle\wrapper\gradle-wrapper.jar'; $hashFile='gradle\wrapper\gradle-wrapper.jar.sha256'; try { Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256' -OutFile $hashFile; $expected=(Get-Content -LiteralPath $hashFile -Raw -Encoding ASCII).Trim().ToLowerInvariant(); if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid official SHA256 format.' }; if (-not (Test-Path -LiteralPath $file)) { Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $file }; $actual=(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant(); if ($actual -ne $expected) { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue; throw 'Gradle wrapper SHA256 mismatch. Please run again.' }; Write-Host 'SHA256 verified. Gradle wrapper is ready.' } finally { Remove-Item -LiteralPath $hashFile -Force -ErrorAction SilentlyContinue }"
if errorlevel 1 (
  echo.
  echo ERROR: Gradle wrapper download or verification failed.
  echo Check your Internet connection and share the complete error output.
  pause
  exit /b 1
)
echo.
echo Setup complete. Open THIS FOLDER in Android Studio.
echo Do not open the app subfolder alone.
echo.
pause
