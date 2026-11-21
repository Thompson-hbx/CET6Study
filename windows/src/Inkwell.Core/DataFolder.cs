using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;

namespace Inkwell.Core;

/// <summary>
/// Where the notebook lives under the user's local app data. The program used to be called Zhupi and kept its
/// notebook in a "Zhupi" folder; the first start under the new name copies that folder across and leaves the
/// original untouched, so going back to an older build still finds everything.
/// </summary>
public static class DataFolder
{
    public const string Name = "Inkwell";
    public const string FormerName = "Zhupi";

    /// <summary>Copies the former folder into place when this is the first start under the new name.</summary>
    /// <returns>True when the former notebook was brought across.</returns>
    public static bool AdoptFormer(string localAppData)
    {
        var target = Path.Combine(localAppData, Name);
        var former = Path.Combine(localAppData, FormerName);
        var formerDb = Path.Combine(former, "study.db");
        if (Directory.Exists(target) || !File.Exists(formerDb)) return false;

        // Use a named OS mutex rather than a persistent lock file so %LOCALAPPDATA% is left clean.
        var fullTarget = Path.TrimEndingDirectorySeparator(Path.GetFullPath(target));
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(fullTarget.ToUpperInvariant())));
        using var adoption = new Mutex(initiallyOwned: false, $"Local\\Inkwell-Adopt-{hash}");
        var acquired = false;
        try
        {
            try
            {
                acquired = adoption.WaitOne();
            }
            catch (AbandonedMutexException)
            {
                acquired = true;
            }
            if (Directory.Exists(target) || !File.Exists(formerDb)) return false;

            var formerLock = Path.Combine(former, ".inkwell.lock");
            if (File.Exists(formerLock))
            {
                try
                {
                    using var probe = File.Open(formerLock, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
                }
                catch (IOException e) when ((e.HResult & 0xffff) is 32 or 33)
                {
                    throw new NotebookException($"旧版本的 {FormerName} 还在运行，请先关闭它再打开。");
                }
            }

            var staging = target + ".copying";
            DeleteStaging(staging);
            if (IsSqliteDatabase(formerDb))
            {
                Copy(new DirectoryInfo(former), staging, skipRootDbFiles: true);
                CopySqliteDatabase(formerDb, Path.Combine(staging, "study.db"));
            }
            else
            {
                Copy(new DirectoryInfo(former), staging, skipRootDbFiles: false);
            }
            Directory.Move(staging, target);
            return true;
        }
        finally
        {
            if (acquired) adoption.ReleaseMutex();
        }
    }

    private static bool IsSqliteDatabase(string path)
    {
        try
        {
            Span<byte> header = stackalloc byte[16];
            using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var read = stream.ReadAtLeast(header, 16, throwOnEndOfStream: false);
            return read == 16 && header.SequenceEqual("SQLite format 3\0"u8);
        }
        catch (IOException e) when ((e.HResult & 0xffff) is 32 or 33)
        {
            throw new NotebookException($"旧版本的 {FormerName} 正在使用本子，请先关闭它再打开。");
        }
    }

    private static void CopySqliteDatabase(string formerDb, string stagingDb)
    {
        try
        {
            using var source = new SqliteConnection(new SqliteConnectionStringBuilder
            {
                DataSource = formerDb,
                Mode = SqliteOpenMode.ReadOnly,
                Pooling = false,
                DefaultTimeout = 1,
            }.ToString());
            source.Open();
            using var dest = new SqliteConnection(new SqliteConnectionStringBuilder
            {
                DataSource = stagingDb,
                Mode = SqliteOpenMode.ReadWriteCreate,
                Pooling = false,
            }.ToString());
            dest.Open();
            source.BackupDatabase(dest);
            return;
        }
        catch (SqliteException e) when (e.SqliteErrorCode is 14 or 8)
        {
            // A cold WAL database without -shm cannot be recovered read-only; fall back only if formerDb is uncontested.
            var wal = formerDb + "-wal";
            if (!File.Exists(wal) || File.Exists(formerDb + "-shm") || !CanOpenExclusively(formerDb) || !CanOpenExclusively(wal))
            {
                throw new NotebookException("无法安全读取旧目录里的本子，没有搬。");
            }
        }
        catch (SqliteException e) when (e.SqliteErrorCode is 5 or 6)
        {
            throw new NotebookException($"旧版本的 {FormerName} 正在写入本子，请先关闭它再打开。");
        }
        catch (SqliteException)
        {
            throw new NotebookException("旧目录里的本子已损坏或无法读取，没有搬。");
        }

        File.Copy(formerDb, stagingDb, overwrite: true);
        File.Copy(formerDb + "-wal", stagingDb + "-wal", overwrite: true);
    }

    private static bool CanOpenExclusively(string path)
    {
        try
        {
            using var stream = File.Open(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static void DeleteStaging(string staging)
    {
        if (!Directory.Exists(staging)) return;
        var root = new DirectoryInfo(staging);
        if ((root.Attributes & FileAttributes.ReparsePoint) != 0)
        {
            if ((root.Attributes & FileAttributes.ReadOnly) != 0) root.Attributes &= ~FileAttributes.ReadOnly;
            root.Delete(recursive: false);
            return;
        }
        ClearReadOnly(root);
        Directory.Delete(staging, recursive: true);
    }

    private static void ClearReadOnly(DirectoryInfo dir)
    {
        foreach (var sub in dir.EnumerateDirectories())
        {
            if ((sub.Attributes & FileAttributes.ReparsePoint) != 0)
            {
                if ((sub.Attributes & FileAttributes.ReadOnly) != 0) sub.Attributes &= ~FileAttributes.ReadOnly;
                sub.Delete(recursive: false);
                continue;
            }
            ClearReadOnly(sub);
        }
        foreach (var file in dir.EnumerateFiles())
        {
            if ((file.Attributes & FileAttributes.ReadOnly) != 0) file.Attributes &= ~FileAttributes.ReadOnly;
        }
        if ((dir.Attributes & FileAttributes.ReadOnly) != 0) dir.Attributes &= ~FileAttributes.ReadOnly;
    }

    private static void Copy(DirectoryInfo from, string to, bool skipRootDbFiles)
    {
        if ((from.Attributes & FileAttributes.ReparsePoint) != 0) return;
        Directory.CreateDirectory(to);
        foreach (var file in from.EnumerateFiles())
        {
            if ((file.Attributes & FileAttributes.ReparsePoint) != 0) continue;
            if (file.Name is ".inkwell.lock") continue;
            if (skipRootDbFiles && file.Name is "study.db" or "study.db-wal" or "study.db-shm") continue;
            file.CopyTo(Path.Combine(to, file.Name));
        }
        foreach (var folder in from.EnumerateDirectories())
        {
            if ((folder.Attributes & FileAttributes.ReparsePoint) != 0) continue;
            Copy(folder, Path.Combine(to, folder.Name), skipRootDbFiles: false);
        }
    }
}
