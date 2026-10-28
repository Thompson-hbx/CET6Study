param([string]$Python = "python")
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat --console=plain --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Android checks failed' }
    & $Python tools/check_schema_migrations.py
    if ($LASTEXITCODE -ne 0) { throw 'Migration checks failed' }
    & $Python -m unittest discover -s tools -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'Tool checks failed' }
    & git diff --check
    if ($LASTEXITCODE -ne 0) { throw 'Diff whitespace checks failed' }
} finally { Pop-Location }
