$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$vendorRoot = Join-Path $root 'third_party'
$destination = Join-Path $vendorRoot 'rubberband-4.0.0'
$single = Join-Path $destination 'single\RubberBandSingle.cpp'
if (Test-Path -LiteralPath $single) {
    Write-Host 'Rubber Band 4.0.0 source already installed.' -ForegroundColor Green
    exit 0
}
New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
$temporary = Join-Path $env:TEMP ('utamaru-rb-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
try {
    $zip = Join-Path $temporary 'rubberband-4.0.0.zip'
    $url = 'https://github.com/breakfastquay/rubberband/archive/refs/tags/v4.0.0.zip'
    Write-Host 'Downloading Rubber Band 4.0.0 official tagged source from GitHub...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Expand-Archive -LiteralPath $zip -DestinationPath $temporary -Force
    $unpacked = Join-Path $temporary 'rubberband-4.0.0'
    if (-not (Test-Path -LiteralPath (Join-Path $unpacked 'single\RubberBandSingle.cpp'))) {
        throw 'Downloaded archive did not contain RubberBandSingle.cpp.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $unpacked 'COPYING'))) {
        throw 'Downloaded archive did not contain the upstream GPL license.'
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Destination already exists but is incomplete: $destination"
    }
    Move-Item -LiteralPath $unpacked -Destination $destination
    Write-Host "SUCCESS: installed official sources into $destination" -ForegroundColor Green
    Write-Host 'Next: Android Studio > Tools > SDK Manager > SDK Tools > NDK (Side by side), CMake.'
    Write-Host 'Then Sync Project with Gradle Files and Generate APKs.'
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'No changes to the currently installed Android APK.'
    exit 1
} finally {
    Remove-Item -LiteralPath $temporary -Force -Recurse -ErrorAction SilentlyContinue
}
