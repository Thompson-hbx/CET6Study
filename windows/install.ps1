<#
.SYNOPSIS
    Installs 墨水瓶 (Inkwell) for the current user: a Release build in a fixed folder plus a Start menu shortcut.

.DESCRIPTION
    No administrator rights are needed. Run it again after pulling changes to update: without -Destination it
    reuses the folder the existing Start menu shortcut points to, so a custom location is only typed once.
    The notebook in %LOCALAPPDATA%\Inkwell is never touched, not even by -Uninstall. The program used to be called
    朱批 (Zhupi): an install made under that name is updated in place, its old shortcut and program files are
    replaced, and the app copies the old %LOCALAPPDATA%\Zhupi notebook across on its first start.
    Your own content pack is copied into <install folder>\packs, and the app imports it on every start
    (repeat-safe). It is a copy for personal use on this machine; the repository never holds it.

.EXAMPLE
    pwsh -File windows/install.ps1 -Destination D:\Software\Inkwell
    pwsh -File windows/install.ps1
    pwsh -File windows/install.ps1 -Pack D:\Packs\my-pack.json
    pwsh -File windows/install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string] $Destination,
    [string] $ShortcutFolder = [Environment]::GetFolderPath('Programs'),
    # The newest personal pack named in docs/EXAM_IMPORT.md; pass -Pack to use another file.
    [string] $Pack = (Join-Path $PSScriptRoot '..\.tools\private-exams\private-study-pack-complete-20261028.json'),
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
$shortcut = Join-Path $ShortcutFolder '墨水瓶.lnk'
$formerShortcut = Join-Path $ShortcutFolder '朱批.lnk'
if (-not $Destination) {
    $existing = @($shortcut, $formerShortcut) | Where-Object { Test-Path $_ } | Select-Object -First 1
    $installed = if ($existing) { (New-Object -ComObject WScript.Shell).CreateShortcut($existing).WorkingDirectory }
    $Destination = if ($installed) { $installed } else { Join-Path $env:LOCALAPPDATA 'Programs\Inkwell' }
}
$exe = Join-Path $Destination 'Inkwell.App.exe'

# Files of a running copy are locked, and replacing them half-way would leave a broken install.
$running = Get-Process -Name 'Inkwell.App', 'Zhupi.App' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($Destination, [StringComparison]::OrdinalIgnoreCase) }
if ($running) {
    throw "墨水瓶正在从 $Destination 运行，请先关掉它再装。"
}

if ($Uninstall) {
    foreach ($link in $shortcut, $formerShortcut) { if (Test-Path $link) { Remove-Item $link } }
    if (Test-Path $Destination) { Remove-Item $Destination -Recurse }
    Write-Host "已卸载程序和开始菜单快捷方式；本子（$env:LOCALAPPDATA\Inkwell）原样保留。"
    return
}

$project = Join-Path $PSScriptRoot 'src\Inkwell.App\Inkwell.App.csproj'
# Framework-dependent: the .NET 10 desktop runtime comes with the SDK this repository already needs.
dotnet publish $project -c Release -r win-x64 --self-contained false -p:PublishSingleFile=false -o $Destination --nologo
if ($LASTEXITCODE -ne 0) { throw "编译失败（dotnet publish 返回 $LASTEXITCODE），没有改动快捷方式。" }
# Program files left from the former name would otherwise sit beside the new ones forever.
Get-ChildItem $Destination -File -Filter 'Zhupi.*' | Remove-Item
if (Test-Path $formerShortcut) { Remove-Item $formerShortcut }

New-Item -ItemType Directory -Force $ShortcutFolder | Out-Null
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.WorkingDirectory = $Destination
$link.IconLocation = "$exe,0"
$link.Description = '墨水瓶：六级作文与翻译练习本'
$link.Save()

$packs = Join-Path $Destination 'packs'
if (Test-Path $Pack) {
    New-Item -ItemType Directory -Force $packs | Out-Null
    Copy-Item $Pack (Join-Path $packs (Split-Path $Pack -Leaf)) -Force
    Write-Host "套卷内容包：已复制到 $packs，下次打开墨水瓶时自动导入"
} elseif (Test-Path $packs) {
    Write-Host "套卷内容包：没找到 $Pack，沿用 $packs 里已有的"
} else {
    Write-Host "套卷内容包：没找到 $Pack，没有打包套卷（可用 -Pack 指定）"
}

Write-Host "已安装到 $Destination"
Write-Host "开始菜单：$shortcut（搜索「墨水瓶」即可打开）"
