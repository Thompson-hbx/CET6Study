param(
    [ValidateSet('run', 'build', 'check')][string]$Action = 'run',
    [string]$RustDirectory = 'C:\Dev\Env\Rust'
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$prototype = Join-Path $repository 'desktop-tauri'
$executable = Join-Path $prototype 'src-tauri\target\release\zhupi-writing-prototype.exe'
if ($Action -eq 'run') {
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Run tools/writing-prototype.ps1 build first.' }
    Start-Process -FilePath $executable -WindowStyle Normal
    return
}
$rustBin = Join-Path $RustDirectory 'cargo\bin'
if (-not (Test-Path -LiteralPath (Join-Path $rustBin 'cargo.exe'))) { throw 'Set -RustDirectory to the existing MSVC Rust installation.' }
$previousCargo = $env:CARGO_HOME
$previousRustup = $env:RUSTUP_HOME
$previousPath = $env:PATH
Push-Location $prototype
try {
    $env:CARGO_HOME = Join-Path $RustDirectory 'cargo'
    $env:RUSTUP_HOME = Join-Path $RustDirectory 'rustup'
    $env:PATH = "$rustBin;$env:PATH"
    if ($Action -eq 'build') {
        & pnpm tauri build --no-bundle
        if ($LASTEXITCODE -ne 0) { throw 'Windows prototype build failed' }
    } else {
        & pnpm check
        if ($LASTEXITCODE -ne 0) { throw 'TypeScript checks failed' }
        & pnpm test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend regression tests failed' }
        & cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
        if ($LASTEXITCODE -ne 0) { throw 'Rust formatting checks failed' }
        & cargo test --locked --manifest-path src-tauri/Cargo.toml
        if ($LASTEXITCODE -ne 0) { throw 'Native storage tests failed' }
    }
} finally {
    Pop-Location
    $env:CARGO_HOME = $previousCargo
    $env:RUSTUP_HOME = $previousRustup
    $env:PATH = $previousPath
}
