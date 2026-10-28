param(
    [string]$Adb = "C:\Dev\Env\Android\Sdk\platform-tools\adb.exe",
    [ValidatePattern('^emulator-\d+$')][string]$Serial = "emulator-5554",
    [ValidateSet('foreground', 'background', 'screen-off', 'pending')]
    [string[]]$Modes = @('foreground', 'background', 'screen-off', 'pending')
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$runId = [guid]::NewGuid().ToString()
$results = @()
$deviceLine = & $Adb devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" }
if (-not $deviceLine) { throw 'Selected emulator is not online; inspect its existing process before retrying' }
$fingerprint = & $Adb -s $Serial shell getprop ro.build.fingerprint
if (($fingerprint -join '') -notmatch 'generic|sdk_gphone') { throw 'Draft process checks require emulator' }

function Read-Probe([string]$Mode) {
    $raw = & $Adb -s $Serial shell run-as com.example.cet6 cat "files/draft-process-$Mode.json" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    try { return (($raw -join "`n") | ConvertFrom-Json) } catch { return $null }
}

foreach ($mode in $Modes) {
    $prefix = Join-Path $repoRoot ".tools/draft-$runId-$mode"
    $prepareLog = "$prefix-prepare.log"
    $prepareErr = "$prefix-prepare.err.log"
    $process = Start-Process -FilePath $Adb -ArgumentList @('-s', $Serial, 'shell', 'am', 'instrument', '--user', '0', '-w',
        '-e', 'scenario', 'draft-process-prepare', '-e', 'mode', $mode, '-e', 'run-id', $runId,
        'com.example.cet6.test/com.example.cet6.LocalDataInstrumentation') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $prepareLog -RedirectStandardError $prepareErr
    $deadline = (Get-Date).AddSeconds(45)
    $transitioned = $false
    $probe = $null
    try {
        do {
            $process.Refresh()
            if ($process.HasExited) {
                Get-Content $prepareLog -Tail 20
                throw 'Preparation ended before a ready marker; do not restart without inspecting the evidence'
            }
            $candidate = Read-Probe $mode
            if ($candidate -and $candidate.runId -eq $runId) {
                if ($candidate.phase -eq 'edited' -and -not $transitioned) {
                    if ($mode -eq 'background') {
                        & $Adb -s $Serial shell input keyevent 3
                        if ($LASTEXITCODE -ne 0) { throw 'Background transition failed' }
                        $transitioned = $true
                    } elseif ($mode -eq 'screen-off') {
                        & $Adb -s $Serial shell input keyevent 223
                        if ($LASTEXITCODE -ne 0) { throw 'Screen-off transition failed' }
                        $transitioned = $true
                    }
                }
                if ($candidate.phase -eq 'ready') { $probe = $candidate; break }
            }
            if ((Get-Date) -ge $deadline) { throw 'Existing preparation is not ready; inspect its process and marker before retrying' }
            Start-Sleep -Milliseconds 150
        } while ($true)
        if ($mode -in @('background', 'screen-off') -and (-not $transitioned -or -not $probe.stopped)) {
            throw 'Lifecycle transition was not observed by the actual Activity'
        }
        if ($mode -eq 'screen-off' -and $probe.interactive) { throw 'Emulator did not enter screen-off state' }
        $liveAppProcess = ((& $Adb -s $Serial shell pidof com.example.cet6) -join '').Trim()
        if ($liveAppProcess -notmatch '^\d+$' -or $liveAppProcess -ne [string]$probe.pid) {
            throw 'Prepared app process changed; refusing to terminate an unverified PID'
        }
        # run-as constrains this signal to the app UID; no force-stop callbacks or data clearing.
        & $Adb -s $Serial shell run-as com.example.cet6 kill -9 $liveAppProcess
        if ($LASTEXITCODE -ne 0) { throw 'App-owned process termination failed' }
        $killDeadline = (Get-Date).AddSeconds(10)
        do {
            $remaining = ((& $Adb -s $Serial shell pidof com.example.cet6) -join '').Trim()
            if ($remaining -notmatch "(^| )$liveAppProcess( |$)") { break }
            if ((Get-Date) -ge $killDeadline) { throw 'Prepared PID is still alive' }
            Start-Sleep -Milliseconds 100
        } while ($true)
        if (-not $process.WaitForExit(10000)) { throw 'App exited but its existing instrumentation client is still running' }
        if ($mode -eq 'screen-off') { & $Adb -s $Serial shell input keyevent 224 }
        $verify = & $Adb -s $Serial shell am instrument --user 0 -w -e scenario draft-process-verify -e mode $mode -e run-id $runId `
            com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
        $verify | Set-Content -Encoding utf8 "$prefix-verify.log"
        $verifyText = $verify -join "`n"
        if ($verifyText -notmatch '(?m)^PASS:' -or $verifyText -match '(?m)^FAIL:') {
            $verify
            throw 'Fresh-process draft verification did not pass'
        }
        $verified = Read-Probe $mode
        if (-not $verified -or $verified.runId -ne $runId -or $verified.phase -ne 'verified' -or $verified.verifiedPid -eq $probe.pid) {
            throw 'Fresh-process verification marker does not match this run'
        }
        $results += [pscustomobject]@{
            mode = $mode; preparedPid = $probe.pid; verifiedPid = $verified.verifiedPid
            acknowledged = $probe.acknowledged; stoppedBeforeKill = $probe.stopped; interactiveBeforeKill = $probe.interactive
            drafts = $verified.drafts; unacknowledgedLatestMissing = $verified.unacknowledgedLatestMissing
            previousPracticesUnchanged = $verified.previousPracticesUnchanged; bankUnchanged = $true
            preparationLog = $prepareLog; verificationLog = "$prefix-verify.log"
        }
        $verify | Where-Object { $_ -match '^PASS:' }
    } finally {
        if ($mode -eq 'screen-off') { & $Adb -s $Serial shell input keyevent 224 | Out-Null }
    }
}
$outputPath = Join-Path $repoRoot ".tools/draft-process-$runId-results.json"
[pscustomobject]@{ runId = $runId; serial = $Serial; results = $results; actualLowMemoryKillTested = $false } |
    ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $outputPath
Write-Output "Evidence: $outputPath"
