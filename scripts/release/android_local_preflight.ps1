[CmdletBinding()]
param(
    [ValidateSet('smoke', 'update')]
    [string]$Mode = 'smoke',
    [string]$AvdName = 'ezbookkeeping_update_api35',
    [string]$ArtifactDir,
    [string]$BaselineApk
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Mode -eq 'update') {
    if ([string]::IsNullOrWhiteSpace($ArtifactDir)) { throw '-ArtifactDir is required in update mode.' }
    if ([string]::IsNullOrWhiteSpace($BaselineApk)) { throw '-BaselineApk is required in update mode.' }
    $ArtifactDir = (Resolve-Path -LiteralPath $ArtifactDir).Path
    $BaselineApk = (Resolve-Path -LiteralPath $BaselineApk).Path
}

function Require-Command([string]$Name, [string]$Purpose) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Missing $Purpose ('$Name'). Install or configure it, then rerun this script; no automatic installation is performed."
    }
    return $command.Source
}

$adb = Require-Command 'adb' 'Android platform tools'
$python = Require-Command 'python' 'Python 3'
$sdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = Split-Path (Split-Path $adb -Parent) -Parent }
$sdkRoot = [IO.Path]::GetFullPath($sdkRoot)
$emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
$avdManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$aapt = Join-Path $sdkRoot 'build-tools\36.0.0\aapt.exe'
$apksigner = Join-Path $sdkRoot 'build-tools\36.0.0\apksigner.bat'
foreach ($requiredPath in @($emulator, $avdManager, $aapt, $apksigner)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing Android SDK component: $requiredPath. Install/configure the required component and rerun."
    }
}

$avdList = & $avdManager list avd 2>&1 | Out-String
if ($avdList -notmatch [regex]::Escape("Name: $AvdName")) {
    throw "Required AVD '$AvdName' is not available. Create/configure it first; this script does not silently substitute another device."
}

$startedByScript = $false
$emulatorProcess = $null
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) 'ezbookkeeping-android-preflight'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$stdoutLog = Join-Path $tempRoot 'emulator.stdout.log'
$stderrLog = Join-Path $tempRoot 'emulator.stderr.log'

try {
    if ($Mode -eq 'smoke') {
        Push-Location (Join-Path $PSScriptRoot '..\..\android')
        try {
            & .\gradlew.bat --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
            if ($LASTEXITCODE -ne 0) { throw "Android debug validation failed with exit code $LASTEXITCODE." }
        }
        finally { Pop-Location }
    }

    $deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^emulator-\d+\s+device\s*$' }
    if (-not $deviceLines) {
        $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList @('-avd', $AvdName, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect') -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
        $startedByScript = $true
    }

    $deviceId = $null
    $booted = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $deviceId = (& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^emulator-\d+\s+device\s*$' } | Select-Object -First 1) -replace '\s+device\s*$', ''
        if (-not [string]::IsNullOrWhiteSpace($deviceId)) { $env:ANDROID_SERIAL = $deviceId }
        $state = (& $adb get-state 2>$null | Out-String).Trim()
        $complete = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($state -eq 'device' -and $complete -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) {
        if (Test-Path $stderrLog) { Get-Content $stderrLog | Select-Object -Last 80 }
        throw "AVD '$AvdName' did not finish booting within 6 minutes."
    }
    if ([string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) { throw 'No emulator device was selected.' }
    $runningAvd = (& $adb emu avd name 2>$null | Out-String).Trim()
    if ($runningAvd -notmatch [regex]::Escape($AvdName)) { throw "Selected emulator is not AVD '$AvdName'." }

    & $adb shell settings put global window_animation_scale 0
    & $adb shell settings put global transition_animation_scale 0
    & $adb shell settings put global animator_duration_scale 0
    if ($Mode -eq 'smoke') {
        $debugApk = Join-Path $PSScriptRoot '..\..\android\app\build\outputs\apk\debug\app-debug.apk'
        $installOutput = & $adb install -r -t $debugApk 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            if ($installOutput -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
                throw "The selected AVD already contains a release-signed app. Use a clean AVD for debug smoke, or provide a release-signed artifact for update mode. Existing app data was not removed."
            }
            throw "Debug APK installation failed: $installOutput"
        }
        & $adb shell am start -n 'top.fjzhang233.books/.MainActivity' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'MainActivity launch failed.' }
        $activity = & $adb shell dumpsys activity activities | Out-String
        if ($activity -notmatch 'top\.fjzhang233\.books') { throw 'The installed app did not become the foreground application.' }
        Write-Host 'Android local debug smoke passed.'
    }

    if ($Mode -eq 'update') {
        & $python -B (Join-Path $PSScriptRoot 'android_upgrade_regression.py') $ArtifactDir --baseline-apk $BaselineApk --aapt $aapt --apksigner $apksigner --adb $adb
        if ($LASTEXITCODE -ne 0) { throw 'Android local upgrade regression failed.' }
    }
}
finally {
    if ($startedByScript) { & $adb emu kill 2>$null | Out-Null }
    Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
}
