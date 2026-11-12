# UtaMaru WindowsReceiver - Wi-Fi launcher (Windows PowerShell 5.1)
$ErrorActionPreference = 'Stop'
$receiverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $receiverDir 'LOCALIZATION.ps1')
$receiverExe = Join-Path $receiverDir 'VoicePitchReceiver_Win64.exe'
if (-not (Test-Path -LiteralPath $receiverExe -PathType Leaf)) {
    Write-Host (T 'wifi_exe_missing') -ForegroundColor Red
    exit 1
}
Write-Host '======================================================='
Write-Host (T 'wifi_header')
Write-Host '======================================================='
Write-Host ''
Write-Host (T 'wifi_trusted')
Write-Host (T 'wifi_vbc_url')
Write-Host (T 'wifi_same_lan')
Write-Host ''
Write-Host (T 'wifi_ipv4')
$ipv4Lines = @(ipconfig.exe | Where-Object { $_ -match 'IPv4' })
if ($ipv4Lines.Count -gt 0) {
    $ipv4Lines | ForEach-Object { Write-Host $_ }
} else {
    Write-Host (T 'wifi_ipv4_none') -ForegroundColor Yellow
}
Write-Host ''
Write-Host (T 'wifi_enter_host')
Write-Host (T 'wifi_app1')
Write-Host (T 'wifi_app2')
Write-Host ''
Write-Host (T 'wifi_playback')
Write-Host (T 'wifi_microphone')
Write-Host ''
Write-Host (T 'wifi_firewall')
Write-Host (T 'wifi_public_warn') -ForegroundColor Yellow
Write-Host ''
& $receiverExe -lan
$receiverExitCode = $LASTEXITCODE
Write-Host ''
Write-Host (T 'wifi_receiver_stopped')
exit $receiverExitCode
