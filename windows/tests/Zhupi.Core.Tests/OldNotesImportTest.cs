using System.Security.Cryptography;
using Microsoft.Data.Sqlite;

namespace Zhupi.Core.Tests;

public sealed class OldNotesImportTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-import-test-");

    public void Dispose() => _dir.Delete(recursive: true);

    /// <summary>Builds a file with the old app's table layout (format 8) holding only what the import reads.</summary>
    private string OldFile(int format = 8, string extraRows = "")
    {
        var path = Path.Combine(_dir.FullName, "cet6_study.db");
        using var db = new SqliteConnection($"Data Source={path};Pooling=False");
        db.Open();
        using var command = db.CreateCommand();
        command.CommandText = $"""
            PRAGMA journal_mode = WAL;
            CREATE TABLE coach_practices (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
                material TEXT NOT NULL, questions TEXT NOT NULL, answer TEXT NOT NULL, feedback TEXT NOT NULL,
                model TEXT NOT NULL, errorReason TEXT NOT NULL, submittedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                savedSentences TEXT NOT NULL DEFAULT '', sourcePracticeId TEXT NOT NULL DEFAULT '',
                practiceGoal TEXT NOT NULL DEFAULT '');
            INSERT INTO coach_practices VALUES
              ('rev', 'WRITING', '夜市', 'Night markets', '', 'People enjoy night markets.', '', '', '', 0, 1700000000000, '', 'orig', ''),
              ('orig', 'WRITING', '夜市', 'Night markets', '', 'People enjoys night markets.', 'old review', 'gpt', '', 1700000500000, 1700000600000, '', '', ''),
              ('tr', 'TRANSLATION', '', '春节是中国最重要的传统节日。', '', 'The Spring Festival...', '', '', '', 1700000700000, 1700000800000, '', 'gone', ''),
              ('rd', 'READING', 'Reading', 'passage', 'q', 'ABCD', '', '', '', 1700000900000, 1700000900000, '', '', '')
              {extraRows};
            PRAGMA user_version = {format};
            """;
        command.ExecuteNonQuery();
        return path;
    }

    private Notebook NewBook() => Notebook.Open(Path.Combine(_dir.FullName, "book", "study.db"), new TickingClock());

    [Fact]
    public void MovesEssaysAndTranslationsAndSkipsTheRest()
    {
        var old = OldFile();
        using var book = NewBook();

        var summary = OldNotesImport.Run(old, book);

        Assert.Equal(new ImportSummary(Essays: 2, Translations: 1, HandedIn: 2, SkippedOther: 1), summary);
        var essays = book.Contents(PieceKind.Essay);
        Assert.Equal(["orig", "rev"], essays.Select(p => p.Id));
        Assert.All(essays, p => Assert.True(p.Imported));
    }

    [Fact]
    public void KeepsTextTimesAndRevisionLinks()
    {
        var old = OldFile();
        using var book = NewBook();

        OldNotesImport.Run(old, book);

        var orig = book.Get("orig");
        Assert.Equal(("夜市", "Night markets", "People enjoys night markets."), (orig.Title, orig.Task, orig.Text));
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1700000500000), orig.HandedInAt);
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1700000600000), orig.UpdatedAt);
        var revision = book.Get("rev");
        Assert.Equal(("orig", false, 1), (revision.Revises, revision.IsHandedIn, revision.Number));
        var translation = book.Get("tr");
        Assert.Null(translation.Revises);
        Assert.Equal((1, "春节是中国最重要的传统节日。"), (translation.Number, translation.Task));
    }

    [Fact]
    public void LeavesTheOldFileByteForByte()
    {
        var old = OldFile();
        var before = Hashes(old);
        using var book = NewBook();

        OldNotesImport.Run(old, book);

        Assert.Equal(before, Hashes(old));
    }

    [Fact]
    public void RefusesANonEmptyNotebookWithoutChangingIt()
    {
        var old = OldFile();
        using var book = NewBook();
        var mine = book.NewPage(PieceKind.Essay);

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(old, book));
        Assert.Equal([mine.Id], book.Contents(PieceKind.Essay).Select(p => p.Id));
        Assert.Empty(book.Contents(PieceKind.Translation));
    }

    [Fact]
    public void RefusesAnUnknownFormat()
    {
        var old = OldFile(format: 7);
        using var book = NewBook();

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(old, book));
        Assert.True(book.IsEmpty);
    }

    private static Dictionary<string, string> Hashes(string path) =>
        new[] { "", "-wal", "-shm" }.Where(s => File.Exists(path + s))
            .ToDictionary(s => s, s => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path + s))));
}
