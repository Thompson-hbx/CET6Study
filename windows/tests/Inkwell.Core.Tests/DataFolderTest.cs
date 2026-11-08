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
    }
}
