# UtaMaru WindowsReceiver - USB connection helper (Windows PowerShell 5.1)
# ADB is NOT included. The user obtains Android SDK Platform-Tools from Google.
$ErrorActionPreference = 'Stop'
$platformToolsUrl = 'https://developer.android.com/tools/releases/platform-tools'
$receiverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $receiverDir 'LOCALIZATION.ps1')
if ($script:UtaLang -eq 'ja') { $platformToolsUrl += '?hl=ja' }
$receiverExe = Join-Path $receiverDir 'VoicePitchReceiver_Win64.exe'
$cacheFile = Join-Path $env:LOCALAPPDATA 'utamaru\WindowsReceiver\adb_path.txt'

function Show-UsbDebugHelp {
    Write-Host ''
    Write-Host (T 'usb_help_title') -ForegroundColor Yellow
    Write-Host (T 'usb_help1')
    Write-Host (T 'usb_help2')
    Write-Host (T 'usb_help3')
    Write-Host (T 'usb_help4')
    Write-Host (T 'usb_help5')
    Write-Host ''
}

function Resolve-AdbPath([string]$item) {
    if ([string]::IsNullOrWhiteSpace($item)) { return $null }
    $item = $item.Trim().Trim('"')
    if (Test-Path -LiteralPath $item -PathType Container) {
        $item = Join-Path $item 'adb.exe'
    }
    if (Test-Path -LiteralPath $item -PathType Leaf) {
        return (Resolve-Path -LiteralPath $item).ProviderPath
    }
    return $null
}

# Capture stdout/stderr from adb without treating its normal startup diagnostics
# on stderr as a terminating PowerShell exception.
function Invoke-Adb([string]$exe, [string[]]$arguments) {
    try {
        $ErrorActionPreference = 'Continue'
        $out = @(& $exe @arguments 2>&1)
        return [PSCustomObject]@{
            Lines = @($out | ForEach-Object { [string]$_ })
            ExitCode = $LASTEXITCODE
        }
    } catch {
        return [PSCustomObject]@{ Lines=@($_.Exception.Message); ExitCode=1 }
    }
}

function Test-Adb([string]$exe) {
    if ([string]::IsNullOrWhiteSpace($exe)) { return $false }
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { return $false }
    if ([System.IO.Path]::GetFileName($exe) -ine 'adb.exe') { return $false }
    $dir = Split-Path -Parent $exe
    foreach ($filename in @('AdbWinApi.dll', 'AdbWinUsbApi.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $dir $filename) -PathType Leaf)) { return $false }
    }
    $result = Invoke-Adb $exe @('version')
    return ($result.ExitCode -eq 0)
}

function Save-Adb([string]$exe) {
    try {
        $dir = Split-Path -Parent $cacheFile
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
            New-Item -Path $dir -ItemType Directory -Force | Out-Null
        }
        Set-Content -LiteralPath $cacheFile -Value $exe -Encoding UTF8
    } catch {
        Write-Host (T 'adb_cache_warn') -ForegroundColor Yellow
    }
}

function Select-AdbFolder {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        $dialog.Description = (T 'adb_folder_dialog')
        $dialog.ShowNewFolderButton = $false
        if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
            return $dialog.SelectedPath
        }
    } catch {
        Write-Host (T 'adb_folder_fallback') -ForegroundColor Yellow
        return (Read-Host (T 'adb_folder_prompt'))
    }
    return $null
}

function Copy-AdbDownloadUrl {
    try {
        Set-Clipboard -Value $platformToolsUrl -ErrorAction Stop
        Write-Host (T 'adb_copy_ok') -ForegroundColor Green
    } catch {
        Write-Host (T 'adb_copy_fail') -ForegroundColor Yellow
    }
}

function Open-AdbDownloadUrl {
    try {
        # Open only the hard-coded Google download page, and only on explicit choice.
        Start-Process -FilePath $platformToolsUrl -ErrorAction Stop
        Write-Host (T 'adb_open_ok') -ForegroundColor Green
    } catch {
        Write-Host ((T 'open_browser_failed') -f $_.Exception.Message) -ForegroundColor Yellow
        Write-Host "URL: $platformToolsUrl"
    }
}

function Get-Adb {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    if (Test-Path -LiteralPath $cacheFile -PathType Leaf) {
        try {
            $saved = (Get-Content -LiteralPath $cacheFile -Raw -Encoding UTF8).Trim()
            if ($saved) { $candidates.Add($saved) }
        } catch { }
    }
    foreach ($envRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($envRoot) { $candidates.Add((Join-Path $envRoot 'platform-tools\adb.exe')) }
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
    }
    $pathAdb = Get-Command 'adb.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pathAdb) { $candidates.Add($pathAdb.Source) }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $exe = Resolve-AdbPath $candidate
        if (Test-Adb $exe) {
            Write-Host ((T 'adb_found') -f $exe) -ForegroundColor Green
            Save-Adb $exe
            return $exe
        }
    }

    while ($true) {
        Write-Host ''
        Write-Host (T 'adb_missing') -ForegroundColor Yellow
        Write-Host ((T 'adb_official') -f $platformToolsUrl) -ForegroundColor Cyan
        Write-Host (T 'adb_option1')
        Write-Host (T 'adb_option2')
        Write-Host (T 'adb_option3')
        Write-Host (T 'option_exit')
        $answer = (Read-Host (T 'prompt_number')).Trim()
        switch ($answer) {
            '1' {
                $folder = Select-AdbFolder
                if (-not $folder) { continue }
                $exe = Resolve-AdbPath $folder
                if (Test-Adb $exe) {
                    Save-Adb $exe
                    Write-Host ((T 'adb_found') -f $exe) -ForegroundColor Green
                    return $exe
                }
                Write-Host (T 'adb_invalid_path') -ForegroundColor Yellow
            }
            '2' { Copy-AdbDownloadUrl }
            '3' { Open-AdbDownloadUrl }
            '0' { return $null }
            default { Write-Host (T 'adb_invalid_choice') }
        }
    }
}

function Get-AuthorizedDevice([string]$adb) {
    while ($true) {
        Write-Host ''
        Write-Host (T 'usb_checking_device')
        $result = Invoke-Adb $adb @('devices', '-l')
        $rawLines = $result.Lines
        $exitCode = $result.ExitCode
        if ($exitCode -ne 0) {
            Write-Host (T 'adb_devices_failed') -ForegroundColor Red
            $rawLines | ForEach-Object { Write-Host "  $_" }
        }

        $found = @()
        foreach ($line in $rawLines) {
            $s = [string]$line
            if ($s -match '^\s*(\S+)\s+(device|unauthorized|offline)\b') {
                $found += [PSCustomObject]@{ Serial=$Matches[1]; State=$Matches[2] }
            }
        }
        $ready = @($found | Where-Object { $_.State -eq 'device' })
        $waiting = @($found | Where-Object { $_.State -ne 'device' })
        foreach ($d in $waiting) {
            if ($d.State -eq 'unauthorized') {
                Write-Host ((T 'usb_unauthorized') -f $d.Serial) -ForegroundColor Yellow
            } else {
                Write-Host ((T 'usb_waiting') -f $d.Serial, $d.State) -ForegroundColor Yellow
            }
        }
        if ($ready.Count -eq 1) {
            Write-Host ((T 'usb_device') -f $ready[0].Serial) -ForegroundColor Green
            return $ready[0].Serial
        }
        if ($ready.Count -gt 1) {
            Write-Host (T 'usb_multiple') -ForegroundColor Yellow
            for ($i = 0; $i -lt $ready.Count; $i++) {
                Write-Host "  [$($i + 1)] $($ready[$i].Serial)"
            }
            Write-Host (T 'usb_retry_exit')
            $choice = (Read-Host (T 'prompt_number')).Trim()
            if ($choice -eq '0') { return $null }
            if ($choice -match '^[Rr]$') { continue }
            if ($choice -match '^\d+$') {
                $number = 0
                if ([int]::TryParse($choice, [ref]$number)) {
                    $index = $number - 1
                    if ($index -ge 0 -and $index -lt $ready.Count) {
                        return $ready[$index].Serial
                    }
                }
            }
            Write-Host (T 'usb_invalid_select')
            continue
        }
        if ($ready.Count -eq 0) {
            Write-Host (T 'usb_none') -ForegroundColor Yellow
            Show-UsbDebugHelp
        }
        Write-Host (T 'usb_enter_exit')
        $answer = Read-Host (T 'usb_prompt_action')
        if ($answer -eq '0') { return $null }
    }
}

Write-Host '========================================================='
Write-Host (T 'usb_header')
Write-Host '========================================================='
Write-Host ''
Write-Host (T 'usb_require')
Write-Host (T 'usb_adb_external')
Write-Host ''

if (-not (Test-Path -LiteralPath $receiverExe -PathType Leaf)) {
    Write-Host (T 'usb_exe_missing') -ForegroundColor Red
    Write-Host (T 'usb_exe_place')
    exit 1
}
$adb = Get-Adb
if (-not $adb) { exit 1 }
$serial = Get-AuthorizedDevice $adb
if (-not $serial) { exit 1 }

Write-Host ''
Write-Host ((T 'usb_setting_reverse') -f $serial)
$reverseResult = Invoke-Adb $adb @('-s', $serial, 'reverse', 'tcp:50005', 'tcp:50005')
$reverseOutput = $reverseResult.Lines
$reverseExitCode = $reverseResult.ExitCode
if ($reverseExitCode -ne 0) {
    Write-Host (T 'usb_reverse_failed') -ForegroundColor Red
    $reverseOutput | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host ''
Write-Host (T 'usb_ready') -ForegroundColor Green
Write-Host (T 'usb_host')
Write-Host (T 'usb_app1')
Write-Host (T 'usb_app2')
Write-Host ''
Write-Host (T 'usb_cable_input')
Write-Host (T 'usb_cable_output')
Write-Host ''
Write-Host (T 'usb_launch_receiver')
Write-Host ''
try {
    & $receiverExe
    $receiverExitCode = $LASTEXITCODE
} finally {
    $null = Invoke-Adb $adb @('-s', $serial, 'reverse', '--remove', 'tcp:50005')
}
if ($receiverExitCode -ne 0) {
    Write-Host ((T 'usb_receiver_failed') -f $receiverExitCode) -ForegroundColor Red
    exit 1
}
Write-Host (T 'usb_receiver_stopped')
exit 0
