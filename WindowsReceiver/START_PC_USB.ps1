# UtaMaru WindowsReceiver - USB connection helper (Windows PowerShell 5.1)
# ADB is NOT included. The user obtains Android SDK Platform-Tools from Google.
$ErrorActionPreference = 'Stop'
$platformToolsUrl = 'https://developer.android.com/tools/releases/platform-tools?hl=ja'
$receiverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$receiverExe = Join-Path $receiverDir 'VoicePitchReceiver_Win64.exe'
$cacheFile = Join-Path $env:LOCALAPPDATA 'utamaru\WindowsReceiver\adb_path.txt'

function Show-UsbDebugHelp {
    Write-Host ''
    Write-Host '【スマホ側の設定（初回）】' -ForegroundColor Yellow
    Write-Host '  1. 設定 → 端末情報など →「ビルド番号」を7回タップ。'
    Write-Host '  2. 開発者向けオプションで「USBデバッグ」をONにする。'
    Write-Host '  3. USBケーブルで接続し、スマホの「USBデバッグを許可しますか？」を承認。'
    Write-Host '  ※ 項目名は機種によって異なります。自分のPCだけを許可してください。'
    Write-Host '  ※ 充電専用ケーブルではADB通信できない場合があります。'
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
        Write-Host '※ ADBの保存先を記憶できませんでした。次回、再指定が必要になる場合があります。' -ForegroundColor Yellow
    }
}

function Select-AdbFolder {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        $dialog.Description = 'adb.exe がある platform-tools フォルダを選択してください'
        $dialog.ShowNewFolderButton = $false
        if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
            return $dialog.SelectedPath
        }
    } catch {
        Write-Host 'フォルダ選択画面を開けませんでした。パスを直接入力してください。' -ForegroundColor Yellow
        return (Read-Host 'platform-tools のフォルダパス（または adb.exe のフルパス）')
    }
    return $null
}

function Copy-AdbDownloadUrl {
    try {
        Set-Clipboard -Value $platformToolsUrl -ErrorAction Stop
        Write-Host 'ADB公式URLをコピーしました。' -ForegroundColor Green
    } catch {
        Write-Host 'コピーできませんでした。表示されているURLを手動でコピーしてください。' -ForegroundColor Yellow
    }
}

function Open-AdbDownloadUrl {
    try {
        # Open only the hard-coded Google download page, and only on explicit choice.
        Start-Process -FilePath $platformToolsUrl -ErrorAction Stop
        Write-Host 'ADB公式ダウンロードページを開きました。' -ForegroundColor Green
    } catch {
        Write-Host "ブラウザを起動できませんでした: $($_.Exception.Message)" -ForegroundColor Yellow
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
            Write-Host "ADBを確認しました: $exe" -ForegroundColor Green
            Save-Adb $exe
            return $exe
        }
    }

    while ($true) {
        Write-Host ''
        Write-Host 'ADB（Android SDK Platform-Tools）が見つかりません。' -ForegroundColor Yellow
        Write-Host "公式ダウンロード先: $platformToolsUrl" -ForegroundColor Cyan
        Write-Host '  [1] ADBのフォルダを指定'
        Write-Host '  [2] ADB公式URLをコピー'
        Write-Host '  [3] ADB公式サイトをブラウザで開く'
        Write-Host '  [0] 終了'
        $answer = (Read-Host '番号を入力').Trim()
        switch ($answer) {
            '1' {
                $folder = Select-AdbFolder
                if (-not $folder) { continue }
                $exe = Resolve-AdbPath $folder
                if (Test-Adb $exe) {
                    Save-Adb $exe
                    Write-Host "ADBを確認しました: $exe" -ForegroundColor Green
                    return $exe
                }
                Write-Host '指定先でADBを実行できませんでした。adb.exe と2つのAdbWin*.dllが同じフォルダにあるか確認してください。' -ForegroundColor Yellow
            }
            '2' { Copy-AdbDownloadUrl }
            '3' { Open-AdbDownloadUrl }
            '0' { return $null }
            default { Write-Host '0、1、2、3 のいずれかを入力してください。' }
        }
    }
}

function Get-AuthorizedDevice([string]$adb) {
    while ($true) {
        Write-Host ''
        Write-Host 'スマホの接続状態を確認しています...'
        $result = Invoke-Adb $adb @('devices', '-l')
        $rawLines = $result.Lines
        $exitCode = $result.ExitCode
        if ($exitCode -ne 0) {
            Write-Host 'ADBの接続状態取得に失敗しました。' -ForegroundColor Red
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
                Write-Host "未承認: $($d.Serial) → スマホ画面でUSBデバッグを許可してください。" -ForegroundColor Yellow
            } else {
                Write-Host "接続待ち: $($d.Serial) ($($d.State)) → USBを差し直すなどして再確認してください。" -ForegroundColor Yellow
            }
        }
        if ($ready.Count -eq 1) {
            Write-Host "接続端末: $($ready[0].Serial)" -ForegroundColor Green
            return $ready[0].Serial
        }
        if ($ready.Count -gt 1) {
            Write-Host '複数の端末が接続されています。使用する端末を選んでください。' -ForegroundColor Yellow
            for ($i = 0; $i -lt $ready.Count; $i++) {
                Write-Host "  [$($i + 1)] $($ready[$i].Serial)"
            }
            Write-Host '  [R] 再検索    [0] 終了'
            $choice = (Read-Host '番号を入力').Trim()
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
            Write-Host '選択が無効です。'
            continue
        }
        if ($ready.Count -eq 0) {
            Write-Host 'USBデバッグで接続済みのスマホが見つかりません。' -ForegroundColor Yellow
            Show-UsbDebugHelp
        }
        Write-Host '  [Enter] 再確認    [0] 終了'
        $answer = Read-Host '操作'
        if ($answer -eq '0') { return $null }
    }
}

Write-Host '========================================================='
Write-Host ' 唄まる -UtaMaru-  Windows PCマイク / USB接続'
Write-Host '========================================================='
Write-Host ''
Write-Host '必要: VB-CABLE、Google SDK Platform-Tools（ADB）、USBデバッグ'
Write-Host '※ ADBは同梱されていません。公式サイトから取得してください。'
Write-Host '※ 公式URLのコピー・ブラウザ起動: COPY_OFFICIAL_URLS.bat'
Write-Host ''

if (-not (Test-Path -LiteralPath $receiverExe -PathType Leaf)) {
    Write-Host 'ERROR: VoicePitchReceiver_Win64.exe が見つかりません。' -ForegroundColor Red
    Write-Host 'WindowsReceiverフォルダ内に受信プログラムを置いてください。'
    exit 1
}
$adb = Get-Adb
if (-not $adb) { exit 1 }
$serial = Get-AuthorizedDevice $adb
if (-not $serial) { exit 1 }

Write-Host ''
Write-Host "USB転送を設定しています: $serial / TCP 50005"
$reverseResult = Invoke-Adb $adb @('-s', $serial, 'reverse', 'tcp:50005', 'tcp:50005')
$reverseOutput = $reverseResult.Lines
$reverseExitCode = $reverseResult.ExitCode
if ($reverseExitCode -ne 0) {
    Write-Host 'ADBポート転送に失敗しました。USBデバッグの許可と接続状態を確認してください。' -ForegroundColor Red
    $reverseOutput | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host ''
Write-Host 'USB接続の準備ができました。' -ForegroundColor Green
Write-Host '  唄まるの接続先（PCホスト）: 127.0.0.1'
Write-Host '  アプリで「マイク入力を音程変換してPCへ送る」'
Write-Host '  または「マイク入力をそのままPCへ送る」を押します。'
Write-Host ''
Write-Host '  WindowsReceiverは「CABLE Input」（再生側）へ音声を出します。'
Write-Host '  Discordなどのマイク入力は「CABLE Output」（録音側）を選びます。'
Write-Host ''
Write-Host '受信ソフトを起動します。終了するとUSB転送設定も解除します。'
Write-Host ''
try {
    & $receiverExe
    $receiverExitCode = $LASTEXITCODE
} finally {
    $null = Invoke-Adb $adb @('-s', $serial, 'reverse', '--remove', 'tcp:50005')
}
if ($receiverExitCode -ne 0) {
    Write-Host "WindowsReceiverがエラー終了しました (exit code: $receiverExitCode)。" -ForegroundColor Red
    exit 1
}
Write-Host 'WindowsReceiverを終了しました。'
exit 0
