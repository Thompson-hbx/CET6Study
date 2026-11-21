using System.Security.Cryptography;
using Microsoft.Data.Sqlite;

namespace Inkwell.Core.Tests;

public sealed class OldNotesImportTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-import-test-");

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
    public void CancelledImportLeavesAnEmptyBook()
    {
        var old = OldFile();
        using var book = NewBook();
        using var cancel = new CancellationTokenSource();
        cancel.Cancel();

        Assert.Throws<OperationCanceledException>(() => OldNotesImport.Run(old, book, cancel.Token));
        Assert.True(book.IsEmpty);
    }

    [Fact]
    public async Task CancelledAsyncImportThrowsOperationCanceledException()
    {
        var old = OldFile();
        using var book = NewBook();
        using var cancel = new CancellationTokenSource();
        cancel.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => OldNotesImport.RunAsync(old, book, cancel.Token));
        Assert.True(book.IsEmpty);
    }

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

    [Fact]
    public void RefusesNonSqliteFilesWithNotebookException()
    {
        var bad = Path.Combine(_dir.FullName, "not-sqlite.db");
        File.WriteAllText(bad, "this is plain text, not sqlite");
        using var book = NewBook();

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(bad, book));
        Assert.True(book.IsEmpty);
    }

    [Fact]
    public void RefusesDuplicateIdsWhenBackupTableHasNoPrimaryKey()
    {
        var path = Path.Combine(_dir.FullName, "dup-id.db");
        using (var db = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            db.Open();
            using var cmd = db.CreateCommand();
            cmd.CommandText = """
                CREATE TABLE coach_practices (id TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
                    material TEXT NOT NULL, answer TEXT NOT NULL, submittedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    sourcePracticeId TEXT NOT NULL DEFAULT '');
                INSERT INTO coach_practices VALUES
                  ('dup', 'WRITING', 't1', 'm1', 'a1', 0, 1700000000000, ''),
                  ('dup', 'WRITING', 't2', 'm2', 'a2', 0, 1700000001000, '');
                PRAGMA user_version = 8;
                """;
            cmd.ExecuteNonQuery();
        }
        using var book = NewBook();

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(path, book));
        Assert.True(book.IsEmpty);
    }

    [Fact]
    public void IgnoresCheckConstraintsDuringQuickCheck()
    {
        var path = Path.Combine(_dir.FullName, "check-constraints.db");
        using (var db = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            db.Open();
            using var cmd = db.CreateCommand();
            cmd.CommandText = """
                PRAGMA ignore_check_constraints = ON;
                CREATE TABLE coach_practices (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL,
                    title TEXT NOT NULL CHECK (length(title) < 0),
                    material TEXT NOT NULL, answer TEXT NOT NULL, submittedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    sourcePracticeId TEXT NOT NULL DEFAULT '');
                INSERT INTO coach_practices VALUES ('ok1', 'WRITING', '夜市', 'Night markets', 'Text', 0, 1700000000000, '');
                PRAGMA user_version = 8;
                """;
            cmd.ExecuteNonQuery();
        }
        using var book = NewBook();

        var summary = OldNotesImport.Run(path, book);

        Assert.Equal(new ImportSummary(Essays: 1, Translations: 0, HandedIn: 0, SkippedOther: 0), summary);
        Assert.Equal("夜市", book.Get("ok1").Title);
    }

    [Theory]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', NULL, 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', 'bad', 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', 1.5, 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', 0, NULL, '')")]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', 0, 'bad', '')")]
    [InlineData("('e1', 'WRITING', NULL, 'm', 'a', 0, 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', 123, 'm', 'a', 0, 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', X'0102', 'm', 'a', 0, 1700000000000, '')")]
    [InlineData("('   ', 'WRITING', 't', 'm', 'a', 0, 1700000000000, '')")]
    [InlineData("('e1', 'WRITING', 't', 'm', 'a', -1, 1700000000000, '')")]
    public void RefusesNullWrongTypeBlankIdAndNegativeSubmittedAt(string valuesSql)
    {
        var path = Path.Combine(_dir.FullName, "bad-types.db");
        using (var db = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            db.Open();
            using var cmd = db.CreateCommand();
            cmd.CommandText = $"""
                CREATE TABLE coach_practices (id, kind, title, material, answer, submittedAt, updatedAt, sourcePracticeId);
                INSERT INTO coach_practices VALUES {valuesSql};
                PRAGMA user_version = 8;
                """;
            cmd.ExecuteNonQuery();
        }
        using var book = NewBook();

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(path, book));
        Assert.True(book.IsEmpty);
    }

    [Fact]
    public void CrossKindSourcePracticeIdDoesNotLinkRevises()
    {
        var old = OldFile(extraRows: """
            , ('es_bad', 'WRITING', 't', 'm', '', 'a', '', '', '', 0, 1700001000000, '', 'tr', '')
            , ('tr_bad', 'TRANSLATION', 't', 'm', '', 'a', '', '', '', 0, 1700001100000, '', 'orig', '')
            """);
        using var book = NewBook();

        var summary = OldNotesImport.Run(old, book);

        Assert.Equal(new ImportSummary(Essays: 3, Translations: 2, HandedIn: 2, SkippedOther: 1), summary);
        Assert.Null(book.Get("es_bad").Revises);
        Assert.Null(book.Get("tr_bad").Revises);
    }

    [Fact]
    public void EmptyBackupTableImportsZeroRowsCleanly()
    {
        var path = Path.Combine(_dir.FullName, "empty.db");
        using (var db = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            db.Open();
            using var cmd = db.CreateCommand();
            cmd.CommandText = """
                CREATE TABLE coach_practices (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
                    material TEXT NOT NULL, answer TEXT NOT NULL, submittedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    sourcePracticeId TEXT NOT NULL DEFAULT '');
                PRAGMA user_version = 8;
                """;
            cmd.ExecuteNonQuery();
        }
        using var book = NewBook();

        Assert.Equal(new ImportSummary(0, 0, 0, 0), OldNotesImport.Run(path, book));
        Assert.True(book.IsEmpty);
    }

    [Fact]
    public void RefusesInUseBackupFileWithNotebookException()
    {
        var old = OldFile();
        using var book = NewBook();
        using var holder = new FileStream(old, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);

        Assert.Throws<NotebookException>(() => OldNotesImport.Run(old, book));
        Assert.True(book.IsEmpty);
    }

    private static Dictionary<string, string> Hashes(string path) =>
        new[] { "", "-wal", "-shm" }.Where(s => File.Exists(path + s))
            .ToDictionary(s => s, s => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path + s))));
}
