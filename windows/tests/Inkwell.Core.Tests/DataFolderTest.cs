using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;

namespace Inkwell.Core.Tests;

public sealed class DataFolderTest : IDisposable
{
    private readonly DirectoryInfo _root = Directory.CreateTempSubdirectory("inkwell-data-");

    public void Dispose() => _root.Delete(recursive: true);

    private string Former => Path.Combine(_root.FullName, DataFolder.FormerName);

    private string Target => Path.Combine(_root.FullName, DataFolder.Name);

    private void Write(string folder, string name, string text)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.Combine(folder, name))!);
        File.WriteAllText(Path.Combine(folder, name), text);
    }

    [Fact]
    public void CopiesTheFormerNotebookAndKeepsTheOriginal()
    {
        Write(Former, "study.db", "db");
        Write(Former, "study.db-wal", "wal");
        Write(Former, "teacher.key", "key");
        Write(Former, Path.Combine("backup", "old.db"), "old");

        Assert.True(DataFolder.AdoptFormer(_root.FullName));

        foreach (var name in new[] { "study.db", "study.db-wal", "teacher.key", Path.Combine("backup", "old.db") })
        {
            Assert.Equal(File.ReadAllText(Path.Combine(Former, name)), File.ReadAllText(Path.Combine(Target, name)));
        }
        Assert.False(Directory.Exists(Target + ".copying"));
    }

    [Fact]
    public void LeavesAnExistingNotebookAlone()
    {
        Write(Former, "study.db", "former");
        Write(Target, "study.db", "current");

        Assert.False(DataFolder.AdoptFormer(_root.FullName));
        Assert.Equal("current", File.ReadAllText(Path.Combine(Target, "study.db")));
    }

    [Fact]
    public void DoesNothingWithoutAFormerNotebook()
    {
        Write(Former, "teacher.key", "key");

        Assert.False(DataFolder.AdoptFormer(_root.FullName));
        Assert.False(Directory.Exists(Target));
    }

    [Fact]
    public void StartsOverAfterAnInterruptedCopy()
    {
        Write(Former, "study.db", "db");
        Write(Target + ".copying", "study.db", "half");

        Assert.True(DataFolder.AdoptFormer(_root.FullName));
        Assert.Equal("db", File.ReadAllText(Path.Combine(Target, "study.db")));
        Assert.False(File.Exists(Target + ".adoption.lock"));
    }

    [Fact]
    public void ClearsReadOnlyAttributesInStagingBeforeStartingOver()
    {
        Write(Former, "study.db", "db");
        var stagingSub = Directory.CreateDirectory(Path.Combine(Target + ".copying", "sub"));
        var readOnlyFile = Path.Combine(stagingSub.FullName, "half.db");
        File.WriteAllText(readOnlyFile, "half");
        File.SetAttributes(readOnlyFile, FileAttributes.ReadOnly);
        stagingSub.Attributes |= FileAttributes.ReadOnly;

        try
        {
            Assert.True(DataFolder.AdoptFormer(_root.FullName));
            Assert.Equal("db", File.ReadAllText(Path.Combine(Target, "study.db")));
            Assert.False(Directory.Exists(Target + ".copying"));
            Assert.False(File.Exists(Target + ".adoption.lock"));
        }
        finally
        {
            if (File.Exists(readOnlyFile)) File.SetAttributes(readOnlyFile, FileAttributes.Normal);
            if (stagingSub.Exists) stagingSub.Attributes &= ~FileAttributes.ReadOnly;
        }
    }

    [Fact]
    public void SkipsReparsePointsWhenCopyingFormerFolder()
    {
        Write(Former, "study.db", "db");
        Write(Former, "teacher.key", "key");
        var outside = Directory.CreateDirectory(Path.Combine(_root.FullName, "outside"));
        File.WriteAllText(Path.Combine(outside.FullName, "secret.txt"), "secret");
        var junction = Path.Combine(Former, "linked");

        using var mklink = Process.Start(new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{junction}\" \"{outside.FullName}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
        });
        mklink!.WaitForExit();
        Assert.Equal(0, mklink.ExitCode);

        try
        {
            Assert.True(DataFolder.AdoptFormer(_root.FullName));
            Assert.Equal("key", File.ReadAllText(Path.Combine(Target, "teacher.key")));
            Assert.False(Directory.Exists(Path.Combine(Target, "linked")));
            Assert.False(File.Exists(Path.Combine(Target, "linked", "secret.txt")));
        }
        finally
        {
            if (Directory.Exists(junction)) Directory.Delete(junction, recursive: false);
        }
    }

    [Fact]
    public void AdoptsLiveWalDatabaseCleanlyWithoutTearing()
    {
        Directory.CreateDirectory(Former);
        var formerDb = Path.Combine(Former, "study.db");
        using (var live = Notebook.Open(formerDb, new TickingClock()))
        {
            var page = live.NewPage(PieceKind.Essay);
            live.Write(page.Id, "夜市", "Write about night markets.", "Committed WAL content.");

            // Keep an uncommitted writer on another connection while adopting to verify WAL snapshot safety.
            using var uncommittedConn = new SqliteConnection($"Data Source={formerDb};Pooling=False");
            uncommittedConn.Open();
            using var tx = uncommittedConn.BeginTransaction();
            using (var cmd = uncommittedConn.CreateCommand())
            {
                cmd.Transaction = tx;
                cmd.CommandText = "UPDATE pieces SET text = 'Uncommitted' WHERE id = $id;";
                cmd.Parameters.AddWithValue("$id", page.Id);
                cmd.ExecuteNonQuery();
            }

            Assert.True(DataFolder.AdoptFormer(_root.FullName));
            tx.Rollback();
        }

        Assert.False(File.Exists(Target + ".adoption.lock"));
        using (var check = new SqliteConnection($"Data Source={Path.Combine(Target, "study.db")};Pooling=False"))
        {
            check.Open();
            using var pragma = check.CreateCommand();
            pragma.CommandText = "PRAGMA integrity_check;";
            Assert.Equal("ok", Convert.ToString(pragma.ExecuteScalar()));
        }
        using var adopted = Notebook.Open(Path.Combine(Target, "study.db"), new TickingClock());
        Assert.Equal("Committed WAL content.", Assert.Single(adopted.Contents(PieceKind.Essay)).Text);
    }

    [Fact]
    public void RefusesAdoptionWhenFormerInkwellLockIsHeld()
    {
        Directory.CreateDirectory(Former);
        Write(Former, "study.db", "db");
        using var lockStream = File.Open(Path.Combine(Former, ".inkwell.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);

        Assert.Throws<NotebookException>(() => DataFolder.AdoptFormer(_root.FullName));
        Assert.False(Directory.Exists(Target));
    }

    [Fact]
    public void RecoversFromAbandonedAdoptionMutex()
    {
        Write(Former, "study.db", "db");
        var fullTarget = Path.TrimEndingDirectorySeparator(Path.GetFullPath(Target));
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(fullTarget.ToUpperInvariant())));
        var thread = new Thread(() =>
        {
            using var mutex = new Mutex(initiallyOwned: false, $"Local\\Inkwell-Adopt-{hash}");
            mutex.WaitOne();
            // Intentionally exit thread without calling ReleaseMutex to simulate crash.
        });
        thread.Start();
        thread.Join();

        Assert.True(DataFolder.AdoptFormer(_root.FullName));
        Assert.Equal("db", File.ReadAllText(Path.Combine(Target, "study.db")));
        Assert.False(File.Exists(Target + ".adoption.lock"));
    }

    [Fact]
    public void AdoptsColdUncheckpointedWalWithoutShm()
    {
        var sourceDir = Path.Combine(_root.FullName, "SourceWal");
        Directory.CreateDirectory(sourceDir);
        var sourceDb = Path.Combine(sourceDir, "study.db");
        using (var conn = new SqliteConnection($"Data Source={sourceDb};Pooling=False"))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                PRAGMA journal_mode = WAL;
                PRAGMA wal_autocheckpoint = 0;
                CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);
                INSERT INTO notes VALUES (1, 'cold wal row');
                """;
            cmd.ExecuteNonQuery();

            Directory.CreateDirectory(Former);
            File.Copy(sourceDb, Path.Combine(Former, "study.db"));
            File.Copy(sourceDb + "-wal", Path.Combine(Former, "study.db-wal"));
        }

        Assert.True(DataFolder.AdoptFormer(_root.FullName));
        Assert.False(File.Exists(Path.Combine(Target, "study.db-shm")));
        using var check = new SqliteConnection($"Data Source={Path.Combine(Target, "study.db")};Pooling=False");
        check.Open();
        using var read = check.CreateCommand();
        read.CommandText = "SELECT body FROM notes WHERE id = 1;";
        Assert.Equal("cold wal row", Convert.ToString(read.ExecuteScalar()));
    }
}
