using System.Diagnostics;
using Microsoft.Data.Sqlite;

namespace Inkwell.Core.Tests;

public sealed class NotebookTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-notebook-");
    private readonly TickingClock _clock = new();
    private readonly Notebook _book;

    public NotebookTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), _clock);

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public void EachBookNumbersItsOwnPages()
    {
        var essay1 = _book.NewPage(PieceKind.Essay);
        var essay2 = _book.NewPage(PieceKind.Essay);
        var translation1 = _book.NewPage(PieceKind.Translation);

        Assert.Equal((1, 2, 1), (essay1.Number, essay2.Number, translation1.Number));
        Assert.Equal([essay2.Id, essay1.Id], _book.Contents(PieceKind.Essay).Select(p => p.Id));
        Assert.Equal(2, _book.Count(PieceKind.Essay));
    }

    [Fact]
    public void DraftsAreSavedAndReadBack()
    {
        var page = _book.NewPage(PieceKind.Essay);

        var saved = _book.Write(page.Id, "经典阅读", "Write about classics.", "Reading classics is never out of date.");

        Assert.True(saved.UpdatedAt > page.UpdatedAt);
        Assert.Equal(saved, _book.Get(page.Id));
    }

    [Fact]
    public void WritingTheSameTextKeepsTheTimestamp()
    {
        var page = _book.Write(_book.NewPage(PieceKind.Essay).Id, "", "task", "text");

        Assert.Equal(page.UpdatedAt, _book.Write(page.Id, "", "task", "text").UpdatedAt);
    }

    [Fact]
    public void AWriteLockDoesNotStallDraftSaving()
    {
        const int sqliteBusy = 5;
        var page = _book.Write(_book.NewPage(PieceKind.Essay).Id, "", "task", "original text");
        using var writer = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(_dir.FullName, "study.db"),
            Pooling = false,
        }.ToString());
        writer.Open();
        using var transaction = writer.BeginTransaction(deferred: false);

        var wait = Stopwatch.StartNew();
        var failure = Assert.Throws<SqliteException>(() => _book.Write(page.Id, page.Title, page.Task, "next text"));
        wait.Stop();

        Assert.Equal(sqliteBusy, failure.SqliteErrorCode);
        Assert.True(wait.Elapsed < TimeSpan.FromSeconds(5), $"Draft save waited {wait.Elapsed} on a write lock.");
        Assert.Equal(page, _book.Get(page.Id));
        transaction.Rollback();

        var saved = _book.Write(page.Id, page.Title, page.Task, "next text");
        Assert.Equal("next text", saved.Text);
        Assert.Equal(saved, _book.Get(page.Id));
    }

    [Fact]
    public void HandingInNeedsTaskAndTextAndThenFreezesThePage()
    {
        var page = _book.NewPage(PieceKind.Translation);
        Assert.Equal("先放入中文原文再交。", Assert.Throws<NotebookException>(() => _book.HandIn(page.Id)).Message);
        _book.Write(page.Id, "", "春节是中国最重要的传统节日。", "");
        Assert.Equal("还没写正文，先写完再交。", Assert.Throws<NotebookException>(() => _book.HandIn(page.Id)).Message);

        _book.Write(page.Id, "", "春节是中国最重要的传统节日。", "The Spring Festival matters most.");
        var handed = _book.HandIn(page.Id);

        Assert.True(handed.IsHandedIn);
        Assert.Equal(handed, _book.Get(page.Id));
        Assert.Throws<NotebookException>(() => _book.Write(page.Id, "", "x", "y"));
        Assert.Throws<NotebookException>(() => _book.HandIn(page.Id));
        Assert.Equal("The Spring Festival matters most.", _book.Get(page.Id).Text);
    }

    [Fact]
    public void RewriteStartsANewPageAndLeavesTheOriginal()
    {
        var page = _book.NewPage(PieceKind.Essay);
        _book.Write(page.Id, "夜市", "Night markets", "People enjoys night markets.");
        var original = _book.HandIn(page.Id);

        var second = _book.Rewrite(page.Id, "People enjoy night markets.");

        Assert.Equal((2, page.Id, false), (second.Number, second.Revises, second.IsHandedIn));
        Assert.Equal(("夜市", "Night markets", "People enjoy night markets."), (second.Title, second.Task, second.Text));
        Assert.Equal(original, _book.Get(page.Id));
        Assert.Equal("People enjoys night markets.", _book.Rewrite(page.Id).Text);
    }

    [Fact]
    public void DraftsCannotBeRewritten()
    {
        var page = _book.NewPage(PieceKind.Essay);

        Assert.Throws<NotebookException>(() => _book.Rewrite(page.Id));
    }

    [Fact]
    public void SearchLooksAtTitleTaskAndTextIgnoringCase()
    {
        var a = _book.Write(_book.NewPage(PieceKind.Essay).Id, "Campus Life", "", "");
        var b = _book.Write(_book.NewPage(PieceKind.Essay).Id, "", "关于阅读", "");
        _book.Write(_book.NewPage(PieceKind.Essay).Id, "", "", "Night markets");

        Assert.Equal([a.Id], _book.Contents(PieceKind.Essay, "campus").Select(p => p.Id));
        Assert.Equal([b.Id], _book.Contents(PieceKind.Essay, "阅读").Select(p => p.Id));
        Assert.Empty(_book.Contents(PieceKind.Translation, "night"));
    }

    [Fact]
    public void ReopeningKeepsPagesAndANewerBookIsRefused()
    {
        var path = Path.Combine(_dir.FullName, "other.db");
        using (var book = Notebook.Open(path, _clock)) book.NewPage(PieceKind.Essay);
        using (var book = Notebook.Open(path, _clock))
        {
            Assert.Equal(1, book.Count(PieceKind.Essay));
        }
        using (var raw = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False"))
        {
            raw.Open();
            using var command = raw.CreateCommand();
            command.CommandText = "PRAGMA user_version = 9;";
            command.ExecuteNonQuery();
        }
        Assert.Throws<NotebookException>(() => Notebook.Open(path, _clock).Dispose());
    }

    [Theory]
    [InlineData(9)]
    [InlineData(-1)]
    public void OpenRejectsFutureAndNegativeUserVersionsWithoutMutatingJournalMode(int badVersion)
    {
        var path = Path.Combine(_dir.FullName, $"bad-v{badVersion}.db");
        using (var raw = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            raw.Open();
            using var command = raw.CreateCommand();
            command.CommandText = $"""
                PRAGMA journal_mode = DELETE;
                CREATE TABLE dummy (x INTEGER);
                PRAGMA user_version = {badVersion};
                """;
            command.ExecuteNonQuery();
        }

        Assert.Throws<NotebookException>(() => Notebook.Open(path, _clock).Dispose());
        Assert.False(File.Exists(path + "-wal"));
        Assert.False(File.Exists(path + "-shm"));
        using (var check = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            check.Open();
            using var cmd = check.CreateCommand();
            cmd.CommandText = "PRAGMA journal_mode;";
            Assert.Equal("delete", Convert.ToString(cmd.ExecuteScalar()));
        }
    }

    [Fact]
    public void MigratesToSchemaVersion8WithIndexesAndPreservesTrashRewrite()
    {
        Assert.Equal(8, Notebook.SchemaVersion);

        var path = Path.Combine(_dir.FullName, "v7.db");
        using (var v7 = Notebook.Open(path, _clock))
        {
            var page = v7.NewPage(PieceKind.Essay);
            v7.Write(page.Id, "夜市", "Night markets", "People enjoy night markets.");
            v7.HandIn(page.Id);
            v7.Discard(page.Id);
        }

        using (var raw = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            raw.Open();
            using var cmd = raw.CreateCommand();
            cmd.CommandText = "PRAGMA user_version = 7; DROP INDEX IF EXISTS reviews_created;";
            cmd.ExecuteNonQuery();
        }

        using var migrated = Notebook.Open(path, _clock);
        using (var verify = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            verify.Open();
            using var cmd = verify.CreateCommand();
            cmd.CommandText = "PRAGMA user_version;";
            Assert.Equal(8L, Convert.ToInt64(cmd.ExecuteScalar()));
            cmd.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN ('pieces_active_kind_number', 'reviews_created', 'papers_exam_date');";
            Assert.Equal(3L, Convert.ToInt64(cmd.ExecuteScalar()));
        }

        // Red line: rewriting a discarded handed-in page from trash remains allowed.
        var trashed = Assert.Single(migrated.Trash(PieceKind.Essay));
        var rewritten = migrated.Rewrite(trashed.Id);
        Assert.Equal(trashed.Id, rewritten.Revises);
    }
}

/// <summary>Advances one second per reading so timestamps are strictly ordered in tests.</summary>
internal sealed class TickingClock : TimeProvider
{
    private DateTimeOffset _now = new(2026, 11, 7, 8, 0, 0, TimeSpan.Zero);

    public override DateTimeOffset GetUtcNow() => _now = _now.AddSeconds(1);
}
