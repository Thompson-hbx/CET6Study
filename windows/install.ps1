<#
.SYNOPSIS
    Installs 朱批 for the current user: a Release build in a fixed folder plus a Start menu shortcut.

.DESCRIPTION
    No administrator rights are needed and nothing outside the current user's profile is touched.
    Run it again after pulling changes to update. The notebook in %LOCALAPPDATA%\Zhupi is never touched,
    not even by -Uninstall.

.EXAMPLE
    pwsh -File windows/install.ps1
    pwsh -File windows/install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $env:LOCALAPPDATA 'Programs\Zhupi'),
    [string] $ShortcutFolder = [Environment]::GetFolderPath('Programs'),
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
$shortcut = Join-Path $ShortcutFolder '朱批.lnk'
$exe = Join-Path $Destination 'Zhupi.App.exe'

# Files of a running copy are locked, and replacing them half-way would leave a broken install.
$running = Get-Process -Name 'Zhupi.App' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($Destination, [StringComparison]::OrdinalIgnoreCase) }
if ($running) {
    throw "朱批正在从 $Destination 运行，请先关掉它再装。"
}

if ($Uninstall) {
    if (Test-Path $shortcut) { Remove-Item $shortcut }
    if (Test-Path $Destination) { Remove-Item $Destination -Recurse }
    Write-Host "已卸载程序和开始菜单快捷方式；本子（$env:LOCALAPPDATA\Zhupi）原样保留。"
    return
}

$project = Join-Path $PSScriptRoot 'src\Zhupi.App\Zhupi.App.csproj'
# Framework-dependent: the .NET 10 desktop runtime comes with the SDK this repository already needs.
dotnet publish $project -c Release -r win-x64 --self-contained false -p:PublishSingleFile=false -o $Destination --nologo
if ($LASTEXITCODE -ne 0) { throw "编译失败（dotnet publish 返回 $LASTEXITCODE），没有改动快捷方式。" }

New-Item -ItemType Directory -Force $ShortcutFolder | Out-Null
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.WorkingDirectory = $Destination
$link.IconLocation = "$exe,0"
$link.Description = '朱批：六级作文与翻译练习本'
$link.Save()

Write-Host "已安装到 $Destination"
Write-Host "开始菜单：$shortcut（搜索「朱批」即可打开）"
