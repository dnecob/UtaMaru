# UtaMaru WindowsReceiver - VB-CABLE startup check (Windows PowerShell 5.1)
# No software is downloaded or installed by this helper.
$ErrorActionPreference = 'Stop'
$officialUrl = 'https://vb-audio.com/Cable/'
. (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'LOCALIZATION.ps1')

function Get-AudioEndpoints {
    # The PnP AudioEndpoint class contains Windows playback and recording devices.
    try {
        if (Get-Command 'Get-PnpDevice' -ErrorAction SilentlyContinue) {
            $items = @(Get-PnpDevice -Class 'AudioEndpoint' -PresentOnly -ErrorAction Stop)
            return [PSCustomObject]@{ Supported = $true; Items = $items }
        }
    } catch { }

    # A fallback for systems where the PnpDevice module is unavailable.
    try {
        $items = @(Get-CimInstance -ClassName 'Win32_PnPEntity' -Filter "PNPClass='AudioEndpoint'" -ErrorAction Stop)
        return [PSCustomObject]@{ Supported = $true; Items = $items }
    } catch {
        return [PSCustomObject]@{ Supported = $false; Items = @() }
    }
}

function Test-VbCable {
    $result = Get-AudioEndpoints
    if (-not $result.Supported) {
        return [PSCustomObject]@{ State = 'Unknown'; InputFound = $false; OutputFound = $false }
    }

    $inputFound = $false
    $outputFound = $false
    foreach ($device in @($result.Items)) {
        if ($null -eq $device -or [string]$device.Status -ne 'OK') { continue }
        $name = [string]$device.FriendlyName
        if ([string]::IsNullOrWhiteSpace($name)) { $name = [string]$device.Name }
        if ($name -match '(?i)\bCABLE\s+Input\b') { $inputFound = $true }
        if ($name -match '(?i)\bCABLE\s+Output\b') { $outputFound = $true }
    }

    $state = 'Missing'
    if ($inputFound -and $outputFound) { $state = 'Ready' }
    return [PSCustomObject]@{
        State = $state
        InputFound = $inputFound
        OutputFound = $outputFound
    }
}

function Copy-OfficialUrl {
    try {
        Set-Clipboard -Value $officialUrl -ErrorAction Stop
        Write-Host (T 'vbc_copy_ok') -ForegroundColor Green
    } catch {
        Write-Host (T 'copy_url_fallback') -ForegroundColor Yellow
    }
}

function Open-OfficialUrl {
    try {
        # Open the fixed official URL only after the user explicitly selects it.
        Start-Process -FilePath $officialUrl -ErrorAction Stop
        Write-Host (T 'vbc_open_ok') -ForegroundColor Green
    } catch {
        Write-Host ((T 'open_browser_failed') -f $_.Exception.Message) -ForegroundColor Yellow
        Write-Host "URL: $officialUrl"
    }
}

Write-Host ''
Write-Host (T 'vbc_title')
while ($true) {
    $test = Test-VbCable
    if ($test.State -eq 'Ready') {
        Write-Host (T 'vbc_ready') -ForegroundColor Green
        Write-Host (T 'vbc_devices')
        Write-Host ''
        exit 0
    }

    Write-Host ''
    if ($test.State -eq 'Unknown') {
        Write-Host (T 'vbc_unknown') -ForegroundColor Yellow
    } else {
        Write-Host (T 'vbc_missing') -ForegroundColor Yellow
        $inputStatus = if ($test.InputFound) { T 'vbc_detected' } else { T 'vbc_not_detected' }
        $outputStatus = if ($test.OutputFound) { T 'vbc_detected' } else { T 'vbc_not_detected' }
        Write-Host ((T 'vbc_input_status') -f $inputStatus)
        Write-Host ((T 'vbc_output_status') -f $outputStatus)
    }
    Write-Host (T 'vbc_possible_causes')
    Write-Host (T 'vbc_check_settings')
    Write-Host ''
    Write-Host (T 'vbc_download') -ForegroundColor Cyan
    Write-Host "  $officialUrl"
    Write-Host (T 'vbc_option1')
    Write-Host (T 'vbc_option2')
    Write-Host (T 'vbc_option3')
    Write-Host (T 'vbc_option4')
    Write-Host (T 'option_exit')
    $choice = ([string](Read-Host (T 'prompt_number'))).Trim()
    switch ($choice) {
        '1' { Copy-OfficialUrl }
        '2' { Open-OfficialUrl }
        '3' { continue }
        '4' {
            Write-Host (T 'vbc_manual_continue') -ForegroundColor Yellow
            exit 0
        }
        '0' { exit 1 }
        default { Write-Host (T 'vbc_invalid') -ForegroundColor Yellow }
    }
}
