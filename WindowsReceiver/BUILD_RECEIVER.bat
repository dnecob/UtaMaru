@echo off
setlocal
cd /d "%~dp0"
where go >nul 2>nul
if errorlevel 1 (
  echo Go toolchain not found. Install Go 1.23.2 or later compatible version:
  echo https://go.dev/dl/
  pause
  exit /b 1
)
echo Building WindowsReceiver for Windows x64...
go version
set "GOOS=windows"
set "GOARCH=amd64"
set "CGO_ENABLED=0"
go build -trimpath -ldflags="-s -w" -o VoicePitchReceiver_Win64.exe src\main.go
if errorlevel 1 (
  echo BUILD FAILED.
  pause
  exit /b 1
)
echo BUILD OK: VoicePitchReceiver_Win64.exe
pause
exit /b 0
