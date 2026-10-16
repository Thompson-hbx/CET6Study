param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
# Debug maintenance snapshot only, not the end-user backup format. Never reads shared_prefs/credentials.
& $Adb -s $Serial shell am force-stop --user 0 com.example.cet6
if ($LASTEXITCODE -ne 0) { throw 'Cannot stop app; no snapshot taken.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
foreach ($dbFile in @('cet6_study.db','cet6_study.db-wal','cet6_study.db-shm')) {
    $target = Join-Path $Destination $dbFile
    if (Test-Path -LiteralPath $target) { throw "Snapshot already exists: $target" }
    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $Adb
    foreach ($argument in @('-s',$Serial,'exec-out','run-as','com.example.cet6','cat',"databases/$dbFile")) {
        $processInfo.ArgumentList.Add($argument)
    }
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($processInfo)
    $fileStream = [System.IO.File]::Create($target)
    try { $process.StandardOutput.BaseStream.CopyTo($fileStream) } finally { $fileStream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Snapshot of $dbFile failed (file may not exist); keep any collected files." }
}
Get-ChildItem -LiteralPath $Destination | Select-Object Name, Length
