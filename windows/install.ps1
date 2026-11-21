<#
.SYNOPSIS
    Installs 墨水瓶 (Inkwell) for the current user: a Release build in a fixed folder plus a Start menu shortcut.

.DESCRIPTION
    Requires PowerShell 7.3+ (pwsh). No administrator rights are needed; running elevated emits a warning because
    installation targets the current user. Run it again after pulling changes to update: without -Destination it
    derives the folder from the existing Start menu shortcut's target path (falling back to working directory if
    empty), so a custom location is only typed once. -ShortcutFolder overrides the Start menu Programs folder.
    Reparse points and junctions in install, shortcut, or pack paths are rejected.
    The notebook in %LOCALAPPDATA%\Inkwell is never touched, not even by -Uninstall. The program used to be called
    朱批 (Zhupi): an install made under that name is updated in place (or when upgrading to a new -Destination),
    its old 朱批.lnk shortcut and Zhupi.* program files are removed, and the app copies the old
    %LOCALAPPDATA%\Zhupi notebook across on its first start.
    Upgrades remove obsolete files dropped by newer builds (preserving modified files with a warning); -Uninstall
    removes only unchanged files from the install manifest or pending install record. Older installs without a
    manifest must be updated first.
    Your own content pack is copied into <install folder>\packs (replacing older *.json packs there), and the app
    imports it on every start (repeat-safe). Passing -Pack with a missing file stops with an error; if -Pack is
    omitted and the default file is absent, existing packs are kept. It is a copy for personal use on this machine;
    the repository never holds it.

.EXAMPLE
    pwsh -File windows/install.ps1 -Destination D:\Software\Inkwell
    pwsh -File windows/install.ps1
    pwsh -File windows/install.ps1 -Pack D:\Packs\my-pack.json
    pwsh -File windows/install.ps1 -ShortcutFolder D:\Links -Uninstall
#>
#Requires -Version 7.3
[CmdletBinding()]
param(
    [string] $Destination,
    [string] $ShortcutFolder = [Environment]::GetFolderPath('Programs'),
    # Default personal pack named in docs/EXAM_IMPORT.md (skipped if absent); explicit -Pack must exist.
    [string] $Pack = (Join-Path $PSScriptRoot '..\.tools\private-exams\private-study-pack-complete-20261028.json'),
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'

# Capture caller FileSystem CWD before any Push-Location (e.g. for dotnet publish / global.json).
$callerDir = (Get-Location -PSProvider FileSystem).ProviderPath

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Warning "当前进程正以管理员权限运行：墨水瓶应安装在当前普通用户目录下，提权运行时路径与快捷方式权限可能与平时不同，建议使用普通权限终端运行。"
}

if ($PSBoundParameters.ContainsKey('Destination') -and [string]::IsNullOrWhiteSpace($Destination)) {
    throw "安装目录（-Destination）不能为空或空白。"
}
if ([string]::IsNullOrWhiteSpace($ShortcutFolder)) {
    throw "快捷方式目录（-ShortcutFolder）不能为空或空白。"
}
if ([string]::IsNullOrWhiteSpace($Pack)) {
    throw "内容包路径（-Pack）不能为空或空白。"
}

$explicitPack = $PSBoundParameters.ContainsKey('Pack')
$ShortcutFolder = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($ShortcutFolder, $callerDir))
$Pack = [IO.Path]::GetFullPath($Pack, $callerDir)

function Assert-PlainPath {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [ValidateSet('Any', 'Directory', 'File')] [string] $ExpectedType = 'Any'
    )
    for ($current = $Path; $current; $current = [IO.Path]::GetDirectoryName($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "安装路径包含链接或重解析点：$current"
            }
            $isDir = [bool]($item.Attributes -band [IO.FileAttributes]::Directory)
            if ($current -ne $Path) {
                if (-not $isDir) {
                    throw "安装路径的上级位置不是目录：$current"
                }
            } elseif ($ExpectedType -eq 'Directory' -and -not $isDir) {
                throw "目标位置已被文件占用（需要目录）：$current"
            } elseif ($ExpectedType -eq 'File' -and $isDir) {
                throw "目标位置已被目录占用（需要文件）：$current"
            }
        }
    }
}

function Get-ProgramFile([string] $RelativePath) {
    $parts = $RelativePath -split '[\\/]'
    $bad = @($parts | Where-Object {
        -not $_ -or $_ -in '.', '..' -or $_.EndsWith('.') -or $_.EndsWith(' ') -or
        $_.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0
    })
    $extension = [IO.Path]::GetExtension($RelativePath)
    if ($bad.Count -or $parts[0] -eq 'packs' -or
        ($extension -notin '.exe', '.dll', '.pdb', '.so', '.dylib' -and
         $RelativePath -notin 'Inkwell.App.deps.json', 'Inkwell.App.runtimeconfig.json')) {
        throw "安装清单含无法识别的程序文件：$RelativePath"
    }

    $path = [IO.Path]::GetFullPath([IO.Path]::Combine($Destination, $RelativePath))
    if (-not $path.StartsWith($Destination + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "安装清单指向目标目录以外：$RelativePath"
    }
    Assert-PlainPath $path -ExpectedType File
    return $path
}

function Read-InstallRecord {
    param(
        [Parameter(Mandatory)] [string] $RecordPath,
        [switch] $AllowDuplicatePaths
    )
    $entries = Get-Content -LiteralPath $RecordPath -Raw | ConvertFrom-Json -NoEnumerate
    if ($entries -isnot [array] -or $entries.path -notcontains 'Inkwell.App.exe') {
        throw "安装清单无法识别：$RecordPath"
    }
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $entries) {
        if ($entry.path -isnot [string] -or $entry.sha256 -isnot [string] -or $entry.sha256 -notmatch '^[0-9a-f]{64}$') {
            throw "安装清单记录不完整：$RecordPath"
        }
        $path = Get-ProgramFile $entry.path
        if (-not $AllowDuplicatePaths -and -not $seen.Add($path)) {
            throw "安装清单中有重复文件：$path"
        }
        [pscustomobject]@{ Path = $path; Relative = $entry.path; Hash = $entry.sha256 }
    }
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory)] [AllowEmptyCollection()] [object[]] $Records,
        [Parameter(Mandatory)] [string] $TargetPath,
        [Parameter(Mandatory)] [ref] $TempOut
    )
    $parent = [IO.Path]::GetDirectoryName($TargetPath)
    [void][IO.Directory]::CreateDirectory($parent)
    $tmp = Join-Path $parent ("{0}.{1}.tmp" -f [IO.Path]::GetFileName($TargetPath), [guid]::NewGuid().ToString('N'))
    $TempOut.Value = $tmp
    $json = ConvertTo-Json -InputObject @($Records) -Depth 4
    Set-Content -LiteralPath $tmp -Value $json -Encoding utf8
    [IO.File]::Move($tmp, $TargetPath, $true)
    $TempOut.Value = $null
}

if (-not ('InkwellShellLink' -as [type])) {
    # Use IShellLinkW + IPersistFile directly: WScript.Shell encodes shortcut paths via the legacy ANSI codepage
    # (CP_ACP), which corrupts "墨水瓶.lnk" to "???.lnk" and fails on non-Chinese Windows locales (e.g. en-US).
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;

public sealed class InkwellShellLinkInfo
{
    public string TargetPath { get; }
    public string WorkingDirectory { get; }

    public InkwellShellLinkInfo(string targetPath, string workingDirectory)
    {
        TargetPath = targetPath;
        WorkingDirectory = workingDirectory;
    }
}

public static class InkwellShellLink
{
    private const int BufferLength = 4096;

    [ComImport]
    [Guid("00021401-0000-0000-C000-000000000046")]
    private class ShellLinkComObject { }

    [ComImport]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    [Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cch, IntPtr pfd, uint fFlags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cch);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cch);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cch);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out ushort pwHotkey);
        void SetHotkey(ushort wHotkey);
        void GetShowCmd(out int piShowCmd);
        void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cch, out int piIcon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
        void Resolve(IntPtr hwnd, uint fFlags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }

    public static InkwellShellLinkInfo Read(string linkPath)
    {
        var link = (IShellLinkW)new ShellLinkComObject();
        try
        {
            ((IPersistFile)link).Load(linkPath, 0);
            var target = new StringBuilder(BufferLength);
            link.GetPath(target, target.Capacity, IntPtr.Zero, 0);
            var workingDir = new StringBuilder(BufferLength);
            link.GetWorkingDirectory(workingDir, workingDir.Capacity);
            return new InkwellShellLinkInfo(target.ToString(), workingDir.ToString());
        }
        finally
        {
            Marshal.FinalReleaseComObject(link);
        }
    }

    public static void Save(string linkPath, string targetPath, string workingDirectory, string iconPath, int iconIndex, string description)
    {
        var link = (IShellLinkW)new ShellLinkComObject();
        try
        {
            link.SetPath(targetPath);
            link.SetWorkingDirectory(workingDirectory);
            link.SetIconLocation(iconPath, iconIndex);
            link.SetDescription(description);
            ((IPersistFile)link).Save(linkPath, true);
        }
        finally
        {
            Marshal.FinalReleaseComObject(link);
        }
    }
}
'@
}

function Read-ProgramShortcut([string] $LinkPath) {
    try {
        return [InkwellShellLink]::Read($LinkPath)
    }
    catch [Runtime.InteropServices.COMException] {
        Write-Warning "无法读取快捷方式，已跳过：$LinkPath"
        return $null
    }
}

function Write-ProgramShortcut {
    param(
        [Parameter(Mandatory)] [string] $LinkPath,
        [Parameter(Mandatory)] [string] $TargetPath,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [string] $Description
    )
    [InkwellShellLink]::Save($LinkPath, $TargetPath, $WorkingDirectory, $TargetPath, 0, $Description)
}

function Remove-ProgramShortcut([string] $LinkPath, [string] $ProgramName) {
    if (Test-Path -LiteralPath $LinkPath -PathType Leaf) {
        Assert-PlainPath $LinkPath -ExpectedType File
        $currentLink = Read-ProgramShortcut $LinkPath
        if ($null -eq $currentLink -or [string]::IsNullOrWhiteSpace($currentLink.TargetPath)) { return }
        $currentTarget = [IO.Path]::GetFullPath($currentLink.TargetPath)
        $target = [IO.Path]::GetFullPath([IO.Path]::Combine($Destination, $ProgramName))
        if ([string]::Equals($currentTarget, $target, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $LinkPath
        }
    }
}

Assert-PlainPath $ShortcutFolder -ExpectedType Directory
$shortcut = Join-Path $ShortcutFolder '墨水瓶.lnk'
$formerShortcut = Join-Path $ShortcutFolder '朱批.lnk'
Assert-PlainPath $shortcut -ExpectedType File
Assert-PlainPath $formerShortcut -ExpectedType File

if (-not $PSBoundParameters.ContainsKey('Destination')) {
    $installed = $null
    foreach ($candidate in @($shortcut, $formerShortcut)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $existingLink = Read-ProgramShortcut $candidate
        if ($null -eq $existingLink) { continue }
        if (-not [string]::IsNullOrWhiteSpace($existingLink.TargetPath)) {
            $derived = [IO.Path]::GetDirectoryName($existingLink.TargetPath)
            if (-not [string]::IsNullOrWhiteSpace($derived)) {
                $installed = $derived
                break
            }
        } elseif (-not [string]::IsNullOrWhiteSpace($existingLink.WorkingDirectory)) {
            $installed = $existingLink.WorkingDirectory
            break
        }
    }
    $Destination = if ($installed) { $installed } else { Join-Path $env:LOCALAPPDATA 'Programs\Inkwell' }
}
$Destination = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Destination, $callerDir))
if ($Destination -eq [IO.Path]::GetPathRoot($Destination)) {
    throw "不能将文件系统根目录作为安装目录：$Destination"
}
Assert-PlainPath $Destination -ExpectedType Directory
$exe = Join-Path $Destination 'Inkwell.App.exe'
$manifest = Join-Path $Destination '.inkwell-install-files.json'
$pendingFile = Join-Path $Destination '.inkwell-install-pending.json'
Assert-PlainPath $manifest -ExpectedType File
Assert-PlainPath $pendingFile -ExpectedType File

# Files of a running copy are locked, and replacing them half-way would leave a broken install.
$destPrefix = $Destination.TrimEnd('\') + '\'
$running = Get-Process -Name 'Inkwell.App', 'Zhupi.App' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($destPrefix, [StringComparison]::OrdinalIgnoreCase) }
if ($running) {
    $action = if ($Uninstall) { '卸载' } else { '安装' }
    throw "墨水瓶正在从 $Destination 运行，请先关掉它再$action。"
}

if ($Uninstall) {
    $hasManifest = Test-Path -LiteralPath $manifest -PathType Leaf
    $hasPending = Test-Path -LiteralPath $pendingFile -PathType Leaf
    if (-not $hasManifest -and -not $hasPending) {
        if (Test-Path -LiteralPath $Destination) {
            throw "没有找到安装清单。旧版请先更新，再卸载。目录和快捷方式未改动。"
        }
    } else {
        $allowedByPath = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.HashSet[string]]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($recordPath in @($manifest, $pendingFile)) {
            if (-not (Test-Path -LiteralPath $recordPath -PathType Leaf)) { continue }
            $allowDups = $recordPath -eq $pendingFile
            foreach ($entry in @(Read-InstallRecord -RecordPath $recordPath -AllowDuplicatePaths:$allowDups)) {
                if (-not $allowedByPath.ContainsKey($entry.Path)) {
                    $allowedByPath[$entry.Path] = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
                }
                [void]$allowedByPath[$entry.Path].Add($entry.Hash)
            }
        }
        foreach ($pair in $allowedByPath.GetEnumerator()) {
            $path = $pair.Key
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
            $diskHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            if (-not $pair.Value.Contains($diskHash)) {
                Write-Warning "文件已变化，保留：$path"
                continue
            }
            Remove-Item -LiteralPath $path
        }
        if ($hasManifest) { Remove-Item -LiteralPath $manifest }
        if ($hasPending) { Remove-Item -LiteralPath $pendingFile }
    }
    Remove-ProgramShortcut $shortcut 'Inkwell.App.exe'
    Remove-ProgramShortcut $formerShortcut 'Zhupi.App.exe'
    Write-Host "已卸载清单中的程序文件。保留本子、内容包和目录中的其他文件。"
    return
}

# Validate pack and packs\ before building/copying so missing explicit -Pack or reparse traps fail fast.
$packs = Join-Path $Destination 'packs'
if ($explicitPack) {
    Assert-PlainPath $Pack -ExpectedType File
    if (-not (Test-Path -LiteralPath $Pack -PathType Leaf)) {
        throw "找不到指定的套卷内容包：$Pack"
    }
    Assert-PlainPath $packs -ExpectedType Directory
} elseif (Test-Path -LiteralPath $Pack) {
    Assert-PlainPath $Pack -ExpectedType File
    if (Test-Path -LiteralPath $Pack -PathType Leaf) {
        Assert-PlainPath $packs -ExpectedType Directory
    } else {
        Assert-PlainPath $packs
    }
} else {
    Assert-PlainPath $packs
}

$project = Join-Path $PSScriptRoot 'src\Inkwell.App\Inkwell.App.csproj'
$stage = $null
$tempFile = $null
try {
    $stage = [IO.Directory]::CreateTempSubdirectory('inkwell-publish-')
    # Run dotnet publish from $PSScriptRoot so windows/global.json governs SDK selection regardless of caller CWD.
    $pushedPublishDir = $false
    try {
        Push-Location -LiteralPath $PSScriptRoot
        $pushedPublishDir = $true
        # Framework-dependent: the .NET 10 desktop runtime comes with the SDK this repository already needs.
        dotnet publish $project -c Release -r win-x64 --self-contained false -p:PublishSingleFile=false -o $stage.FullName --nologo
        $publishExit = $LASTEXITCODE
    }
    finally {
        if ($pushedPublishDir) {
            Pop-Location
        }
        # Pop-Location restores a non-FileSystem active location (e.g. Env:\) but leaves the FileSystem
        # provider CWD at $PSScriptRoot, so restore $callerDir explicitly for subsequent relative-path calls.
        if ((Get-Location).Provider.Name -ne 'FileSystem' -and
            -not [string]::IsNullOrWhiteSpace($callerDir) -and
            (Test-Path -LiteralPath $callerDir -PathType Container)) {
            Push-Location -LiteralPath $callerDir
            Pop-Location
        }
    }
    if ($publishExit -ne 0) { throw "编译失败（dotnet publish 返回 $publishExit），没有改动安装目录或快捷方式。" }

    $publishFiles = @($stage.EnumerateFiles('*', [IO.SearchOption]::AllDirectories))
    if (-not ($publishFiles.FullName -contains [IO.Path]::Combine($stage.FullName, 'Inkwell.App.exe'))) {
        throw '发布产物缺少 Inkwell.App.exe。'
    }

    $files = foreach ($file in $publishFiles) {
        $relative = [IO.Path]::GetRelativePath($stage.FullName, $file.FullName)
        [pscustomobject]@{
            Source = $file.FullName
            Target = Get-ProgramFile $relative
            Entry = @{ path = $relative; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
        }
    }

    $previous = @{}
    if (Test-Path -LiteralPath $manifest -PathType Leaf) {
        foreach ($entry in @(Read-InstallRecord -RecordPath $manifest)) {
            $previous[$entry.Path] = $entry
        }
    }

    $pending = @{}
    $pendingEntries = @()
    if (Test-Path -LiteralPath $pendingFile -PathType Leaf) {
        foreach ($entry in @(Read-InstallRecord -RecordPath $pendingFile -AllowDuplicatePaths)) {
            if (-not $pending.ContainsKey($entry.Path)) {
                $pending[$entry.Path] = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            }
            [void]$pending[$entry.Path].Add($entry.Hash)
            $pendingEntries += @{ path = $entry.Relative; sha256 = $entry.Hash }
        }
    }

    $legacyProduct = $false
    if ($previous.Count -eq 0) {
        foreach ($candidate in @(
            @{ Exe = $exe; Dll = (Get-ProgramFile 'Inkwell.App.dll'); Name = 'Inkwell.App' },
            @{ Exe = (Get-ProgramFile 'Zhupi.App.exe'); Dll = (Get-ProgramFile 'Zhupi.App.dll'); Name = 'Zhupi.App' }
        )) {
            if ([IO.File]::Exists($candidate.Exe) -and [IO.File]::Exists($candidate.Dll)) {
                try {
                    if ([Reflection.AssemblyName]::GetAssemblyName($candidate.Dll).Name -eq $candidate.Name) {
                        $legacyProduct = $true
                        break
                    }
                }
                catch [BadImageFormatException] {}
            }
        }
    }
    $legacyFiles = @(
        'Inkwell.App.exe'
        'Inkwell.App.dll'
        'Inkwell.App.pdb'
        'Inkwell.App.deps.json'
        'Inkwell.App.runtimeconfig.json'
        'Inkwell.Core.dll'
        'Inkwell.Core.pdb'
        'CommunityToolkit.Mvvm.dll'
        'Microsoft.Data.Sqlite.dll'
        'SQLitePCLRaw.batteries_v2.dll'
        'SQLitePCLRaw.core.dll'
        'SQLitePCLRaw.provider.e_sqlite3.dll'
        'e_sqlite3.dll'
    )

    # Validate ownership of all pre-existing targets and build $owned solely from the new publish set.
    $owned = @{}
    $toCopy = foreach ($file in $files) {
        if (Test-Path -LiteralPath $file.Target -PathType Container) {
            throw "程序文件位置已被目录占用：$($file.Target)"
        }
        $shouldCopy = $true
        if ([IO.File]::Exists($file.Target)) {
            $hash = (Get-FileHash -LiteralPath $file.Target -Algorithm SHA256).Hash
            $wasOwned = ($previous.ContainsKey($file.Target) -and $previous[$file.Target].Hash -eq $hash) -or
                        ($pending.ContainsKey($file.Target) -and $pending[$file.Target].Contains($hash)) -or
                        ($legacyProduct -and $file.Entry.path -in $legacyFiles)
            if (-not $wasOwned) {
                throw "目标中已有无法确认归属的同名文件，请使用其他安装目录：$($file.Target)"
            }
            if ($hash -eq $file.Entry.sha256) {
                $shouldCopy = $false
            }
        }
        $owned[$file.Target] = $file.Entry
        if ($shouldCopy) { $file }
    }

    # Persist cumulative pending hashes before mutating $Destination so aborted copies can resume safely.
    [void][IO.Directory]::CreateDirectory($Destination)
    $pendingSeen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $cumulativePending = foreach ($item in (@($pendingEntries) + @($files | ForEach-Object { $_.Entry }))) {
        $key = "$($item.path)`0$($item.sha256)"
        if ($pendingSeen.Add($key)) { $item }
    }
    Write-JsonAtomic -Records @($cumulativePending) -TargetPath $pendingFile -TempOut ([ref]$tempFile)

    foreach ($file in $toCopy) {
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file.Target))
        [IO.File]::Copy($file.Source, $file.Target, $true)
    }

    # Remove obsolete files from the previous build or aborted pending installs before replacing $manifest.
    $obsoletePaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $previous.Keys) { [void]$obsoletePaths.Add($path) }
    foreach ($path in $pending.Keys) { [void]$obsoletePaths.Add($path) }
    foreach ($obsoletePath in $obsoletePaths) {
        if ($owned.ContainsKey($obsoletePath)) { continue }
        if (-not (Test-Path -LiteralPath $obsoletePath -PathType Leaf)) { continue }
        Assert-PlainPath $obsoletePath -ExpectedType File
        $diskHash = (Get-FileHash -LiteralPath $obsoletePath -Algorithm SHA256).Hash
        $matchesPrevious = $previous.ContainsKey($obsoletePath) -and $previous[$obsoletePath].Hash -eq $diskHash
        $matchesPending = $pending.ContainsKey($obsoletePath) -and $pending[$obsoletePath].Contains($diskHash)
        if (-not $matchesPrevious -and -not $matchesPending) {
            Write-Warning "旧版文件已变化，保留：$obsoletePath"
            continue
        }
        Remove-Item -LiteralPath $obsoletePath
    }

    Write-JsonAtomic -Records @($owned.Values) -TargetPath $manifest -TempOut ([ref]$tempFile)
    if (Test-Path -LiteralPath $pendingFile -PathType Leaf) {
        Remove-Item -LiteralPath $pendingFile
    }

    # Remove legacy Zhupi.* files only after $manifest is finalized so aborted runs still detect legacy Zhupi.
    Get-ChildItem -LiteralPath $Destination -File -Filter 'Zhupi.*' | ForEach-Object {
        Assert-PlainPath $_.FullName -ExpectedType File
        Remove-Item -LiteralPath $_.FullName
    }
}
finally {
    if ($tempFile -and (Test-Path -LiteralPath $tempFile -PathType Leaf)) {
        try { Remove-Item -LiteralPath $tempFile -Force } catch { Write-Warning "清理临时文件失败：$tempFile" }
    }
    if ($null -ne $stage -and $stage.Exists -and
        $stage.Parent.FullName.TrimEnd('\') -eq [IO.Path]::GetTempPath().TrimEnd('\')) {
        try { $stage.Delete($true) } catch { Write-Warning "清理临时目录失败：$($stage.FullName)" }
    }
}

# Remove stale 朱批.lnk even when upgrading to a new -Destination, while preserving unrelated shortcuts.
if (Test-Path -LiteralPath $formerShortcut -PathType Leaf) {
    Assert-PlainPath $formerShortcut -ExpectedType File
    $formerLink = Read-ProgramShortcut $formerShortcut
    if ($null -ne $formerLink -and
        -not [string]::IsNullOrWhiteSpace($formerLink.TargetPath) -and
        [string]::Equals([IO.Path]::GetFileName($formerLink.TargetPath), 'Zhupi.App.exe', [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $formerShortcut
    }
}

[void][IO.Directory]::CreateDirectory($ShortcutFolder)
Assert-PlainPath $shortcut -ExpectedType File
Write-ProgramShortcut -LinkPath $shortcut -TargetPath $exe -WorkingDirectory $Destination -Description '墨水瓶：六级作文与翻译练习本'

if (Test-Path -LiteralPath $Pack -PathType Leaf) {
    Assert-PlainPath $Pack -ExpectedType File
    Assert-PlainPath $packs -ExpectedType Directory
    [void][IO.Directory]::CreateDirectory($packs)
    $installedPack = Join-Path $packs ([IO.Path]::GetFileName($Pack))
    Assert-PlainPath $installedPack -ExpectedType File
    if (-not [string]::Equals($Pack, $installedPack, [StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $Pack -Destination $installedPack -Force
    }
    $removedOldPacks = $false
    foreach ($oldPack in @(Get-ChildItem -LiteralPath $packs -File -Filter '*.json')) {
        if (-not [string]::Equals($oldPack.FullName, $installedPack, [StringComparison]::OrdinalIgnoreCase)) {
            Assert-PlainPath $oldPack.FullName -ExpectedType File
            Remove-Item -LiteralPath $oldPack.FullName
            $removedOldPacks = $true
        }
    }
    if ($removedOldPacks) {
        Write-Host "套卷内容包：已复制到 $packs（已清理旧内容包），下次打开墨水瓶时自动导入"
    } else {
        Write-Host "套卷内容包：已复制到 $packs，下次打开墨水瓶时自动导入"
    }
} elseif ((Test-Path -LiteralPath $packs -PathType Container) -and
          @(Get-ChildItem -LiteralPath $packs -File -Filter '*.json').Count -gt 0) {
    Write-Host "套卷内容包：没找到 $Pack，沿用 $packs 里已有的"
} else {
    Write-Host "套卷内容包：没找到 $Pack，没有打包套卷（可用 -Pack 指定）"
}

Write-Host "已安装到 $Destination"
Write-Host "开始菜单：$shortcut（搜索「墨水瓶」即可打开）"
