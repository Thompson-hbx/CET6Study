param(
    [string]$Adb = "C:\Dev\Env\Android\Sdk\platform-tools\adb.exe",
    [ValidatePattern('^emulator-\d+$')][string]$Serial = "emulator-5554",
    [string]$ResumeRunId = ""
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$runId = if ($ResumeRunId) { ([guid]::Parse($ResumeRunId)).ToString() } else { [guid]::NewGuid().ToString() }
$prefix = Join-Path $repoRoot ".tools/low-memory-$runId"
Write-Output "Run: $runId; evidence prefix: $prefix"
$deviceLine = & $Adb devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" }
if (-not $deviceLine) { throw 'Selected emulator is not online' }
$fingerprint = & $Adb -s $Serial shell getprop ro.build.fingerprint
if (($fingerprint -join '') -notmatch 'generic|sdk_gphone') { throw 'Memory pressure requires emulator' }

if (-not $ResumeRunId) {
    $prepare = & $Adb -s $Serial shell am instrument --user 0 -w -e scenario draft-process-prepare -e mode low-memory -e run-id $runId `
        com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
    $prepare | Set-Content -Encoding utf8 "$prefix-prepare.log"
    $prepareText = $prepare -join "`n"
    if ($LASTEXITCODE -ne 0 -or $prepareText -notmatch '(?m)^PREPARED:' -or $prepareText -match '(?m)^FAIL:') {
        throw 'Low-memory preparation did not finish; inspect the preserved log'
    }
    $prepare | Where-Object { $_ -match '^PREPARED:' }
} elseif (Test-Path -LiteralPath "$prefix-pressure-launch.log") {
    throw 'Pressure was already attempted for this run; inspect that operation instead of starting it again'
}
$probe = ((& $Adb -s $Serial shell run-as com.example.cet6 cat "files/draft-process-low-memory-$runId.json") -join "`n") | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $probe.runId -ne $runId -or $probe.phase -ne 'ready' -or -not $probe.acknowledged) {
    throw 'Preparation marker is not the acknowledged fixture for this run'
}
# No instrumentation may pin the target's process while the system chooses a cached victim.
if (-not $ResumeRunId) {
    & $Adb -s $Serial shell am start -W -n com.example.cet6/com.example.cet6.MainActivity > "$prefix-normal-launch.log"
    if ($LASTEXITCODE -ne 0) { throw 'Normal study Activity did not start' }
    & $Adb -s $Serial shell input keyevent 3
    if ($LASTEXITCODE -ne 0) { throw 'Could not background the normal study Activity' }
}
$deadline = (Get-Date).AddSeconds(15)
$targetProcessId = ''
$oomAdj = -1
do {
    $candidate = ((& $Adb -s $Serial shell pidof com.example.cet6) -join '').Trim()
    if ($candidate -match '^\d+$') {
        $rawAdj = ((& $Adb -s $Serial shell cat "/proc/$candidate/oom_score_adj") -join '').Trim()
        if ($LASTEXITCODE -eq 0 -and $rawAdj -match '^\d+$' -and [int]$rawAdj -ge 900) {
            $targetProcessId = $candidate
            $oomAdj = [int]$rawAdj
            break
        }
    }
    if ((Get-Date) -ge $deadline) { throw 'Normal app did not become a verified cached process; no pressure started' }
    Start-Sleep -Milliseconds 150
} while ($true)
Write-Output "Cached normal process verified: pid=$targetProcessId; oom_score_adj=$oomAdj"
$existingPressure = ((& $Adb -s $Serial shell pidof com.example.cet6.test) -join '').Trim()
if ($existingPressure) { throw 'Another pressure process is already present; inspect it before starting a new one' }
$pressureStarted = $false
try {
    $pressureStarted = $true
    & $Adb -s $Serial shell am start -W -n com.example.cet6.test/com.example.cet6.MemoryPressureActivity `
        --es consent original-draft-lmk-check --es run-id $runId > "$prefix-pressure-launch.log"
    if ($LASTEXITCODE -ne 0) { throw 'Emulator-only pressure Activity did not start' }
    $pressureDeadline = (Get-Date).AddSeconds(8)
    do {
        $livePressure = ((& $Adb -s $Serial shell pidof com.example.cet6.test) -join '').Trim()
        $readyPressure = $null
        if ($livePressure -match '^\d+$') {
            $readyRaw = & $Adb -s $Serial shell run-as com.example.cet6.test cat "files/low-memory-pressure-$runId.json" 2>$null
            if ($LASTEXITCODE -eq 0) { $readyPressure = ($readyRaw -join "`n") | ConvertFrom-Json }
        }
        if ($readyPressure -and $readyPressure.runId -eq $runId -and $readyPressure.allocatedBytes -gt 0) { break }
        if ((Get-Date) -ge $pressureDeadline) { throw 'Pressure process did not become live with a matching allocation report' }
        Start-Sleep -Milliseconds 100
    } while ($true)
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $remaining = ((& $Adb -s $Serial shell pidof com.example.cet6) -join '').Trim()
        if ($LASTEXITCODE -notin @(0, 1)) { throw 'Cannot observe the prepared app process' }
        if ($remaining -notmatch "(^| )$targetProcessId( |$)") { break }
        if ((Get-Date) -ge $deadline) { throw 'System did not reclaim the cached target within the bounded pressure window' }
        Start-Sleep -Milliseconds 200
    } while ($true)
} finally {
    # UID-scoped cleanup does not depend on ActivityManager responding under pressure.
    if ($pressureStarted) {
        $ownedPressurePid = ((& $Adb -s $Serial shell pidof com.example.cet6.test) -join '').Trim()
        if ($ownedPressurePid) {
            if ($ownedPressurePid -notmatch '^\d+$') { throw 'Cannot identify a single pressure process for cleanup' }
            & $Adb -s $Serial shell run-as com.example.cet6.test kill -9 $ownedPressurePid
            if ($LASTEXITCODE -ne 0) { throw 'Could not release the pressure package with its own UID' }
        }
    }
}
$pressureRaw = & $Adb -s $Serial shell run-as com.example.cet6.test cat "files/low-memory-pressure-$runId.json"
if ($LASTEXITCODE -ne 0) { throw 'Pressure metadata missing; preserve the run before retrying' }
$pressure = ($pressureRaw -join "`n") | ConvertFrom-Json
if ($pressure.runId -ne $runId) { throw 'Pressure metadata belongs to another run' }
$pressureRaw | Set-Content -Encoding utf8 "$prefix-pressure.json"
$systemExit = & $Adb -s $Serial shell dumpsys activity exit-info com.example.cet6
$systemExit | Set-Content -Encoding utf8 "$prefix-exit-info.log"
$verify = & $Adb -s $Serial shell am instrument --user 0 -w -e scenario draft-process-verify -e mode low-memory -e run-id $runId `
    -e killed-pid $targetProcessId com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
$verify | Set-Content -Encoding utf8 "$prefix-verify.log"
$verifyText = $verify -join "`n"
if ($LASTEXITCODE -ne 0 -or $verifyText -notmatch '(?m)^PASS:' -or $verifyText -match '(?m)^FAIL:') {
    throw 'System low-memory exit and fresh-process draft verification did not pass; inspect preserved logs'
}
$verified = ((& $Adb -s $Serial shell run-as com.example.cet6 cat "files/draft-process-low-memory-$runId.json") -join "`n") | ConvertFrom-Json
if ($verified.phase -ne 'verified' -or $verified.runId -ne $runId -or [string]$verified.lowMemoryExit.pid -ne $targetProcessId -or
    [string]$verified.verifiedPid -eq $targetProcessId) { throw 'Verified low-memory marker does not match the killed normal process' }
[pscustomobject]@{
    runId = $runId; serial = $Serial; resumedAfterCachedObservation = [bool]$ResumeRunId; normalProcessPid = [int]$targetProcessId; cachedOomScoreAdj = $oomAdj
    actualLowMemoryKillTested = $true; exitInfo = $verified.lowMemoryExit; verifiedPid = $verified.verifiedPid
    acknowledgedDraftsRestored = $verified.drafts; previousPracticesUnchanged = $verified.previousPracticesUnchanged
    bankUnchanged = $true; pressure = $pressure
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 "$prefix-results.json"
$verify | Where-Object { $_ -match '^PASS:' }
Write-Output "Evidence: $prefix-results.json"
