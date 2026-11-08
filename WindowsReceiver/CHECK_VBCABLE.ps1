# UtaMaru WindowsReceiver - VB-CABLE startup check (Windows PowerShell 5.1)
# No software is downloaded or installed by this helper.
$ErrorActionPreference = 'Stop'
$officialUrl = 'https://vb-audio.com/Cable/'

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
        Write-Host '公式URLをコピーしました。' -ForegroundColor Green
    } catch {
        Write-Host 'URLをコピーできませんでした。表示されているURLを手動でコピーしてください。' -ForegroundColor Yellow
    }
}

function Open-OfficialUrl {
    try {
        # Open the fixed official URL only after the user explicitly selects it.
        Start-Process -FilePath $officialUrl -ErrorAction Stop
        Write-Host '公式ページをブラウザで開きました。' -ForegroundColor Green
    } catch {
        Write-Host "ブラウザを起動できませんでした: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "URL: $officialUrl"
    }
}

Write-Host ''
Write-Host '--- VB-CABLE チェック ---'
while ($true) {
    $test = Test-VbCable
    if ($test.State -eq 'Ready') {
        Write-Host 'VB-CABLEの再生・録音デバイスを確認しました。' -ForegroundColor Green
        Write-Host '  再生先: CABLE Input / マイク入力: CABLE Output'
        Write-Host ''
        exit 0
    }

    Write-Host ''
    if ($test.State -eq 'Unknown') {
        Write-Host 'Windowsの音声デバイス情報を取得できませんでした。' -ForegroundColor Yellow
    } else {
        Write-Host 'VB-CABLEの必要な音声デバイスを両方とも確認できませんでした。' -ForegroundColor Yellow
        $inputStatus = if ($test.InputFound) { '検出' } else { '未検出' }
        $outputStatus = if ($test.OutputFound) { '検出' } else { '未検出' }
        Write-Host "  CABLE Input (再生): $inputStatus"
        Write-Host "  CABLE Output (録音): $outputStatus"
    }
    Write-Host '未インストール、再起動待ち、デバイス無効化、名称変更などの可能性があります。'
    Write-Host 'Windowsの「設定 → システム → サウンド」でも確認できます。'
    Write-Host ''
    Write-Host 'VB-CABLE公式ダウンロード先:' -ForegroundColor Cyan
    Write-Host "  $officialUrl"
    Write-Host '  [1] VB-CABLE公式URLをコピー'
    Write-Host '  [2] VB-CABLE公式サイトをブラウザで開く'
    Write-Host '  [3] 再確認（インストール・再起動後など）'
    Write-Host '  [4] Windowsで確認済みなので、そのまま続行'
    Write-Host '  [0] 終了'
    $choice = ([string](Read-Host '番号を入力')).Trim()
    switch ($choice) {
        '1' { Copy-OfficialUrl }
        '2' { Open-OfficialUrl }
        '3' { continue }
        '4' {
            Write-Host '手動確認として続行します。CABLE Outputをマイクに指定してください。' -ForegroundColor Yellow
            exit 0
        }
        '0' { exit 1 }
        default { Write-Host '0、1、2、3、4 のいずれかを入力してください。' -ForegroundColor Yellow }
    }
}
