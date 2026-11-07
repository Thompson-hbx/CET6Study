using Microsoft.Data.Sqlite;

namespace Zhupi.Core;

/// <summary>
/// The learner's notebook, one SQLite file. Each kind has its own page numbering, like separate exercise books.
/// Schema changes only ever add tables or columns.
/// </summary>
public sealed class Notebook : IDisposable
{
    public const int SchemaVersion = 1;

    private const string Columns = "id, kind, number, title, task, text, revises, created_at, updated_at, handed_in_at, origin";

    private readonly SqliteConnection _db;
    private readonly TimeProvider _clock;

    private Notebook(SqliteConnection db, TimeProvider clock)
    {
        _db = db;
        _clock = clock;
    }

    public static Notebook Open(string path, TimeProvider clock)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var db = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            ForeignKeys = true,
            Pooling = false,
        }.ToString());
        db.Open();
        try
        {
            Exec(db, "PRAGMA journal_mode = WAL;");
            Migrate(db);
            return new Notebook(db, clock);
        }
        catch
        {
            db.Dispose();
            throw;
        }
    }

    private static void Migrate(SqliteConnection db)
    {
        var version = Convert.ToInt32(Scalar(db, "PRAGMA user_version;"));
        if (version > SchemaVersion) throw new NotebookException("这个本子由更新版本的朱批写过，请升级后再打开。");
        if (version == SchemaVersion) return;
        using var tx = db.BeginTransaction();
        Exec(db, """
            CREATE TABLE pieces (
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('essay', 'translation')),
                number INTEGER NOT NULL CHECK (number > 0),
                title TEXT NOT NULL,
                task TEXT NOT NULL,
                text TEXT NOT NULL,
                revises TEXT REFERENCES pieces (id),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                handed_in_at INTEGER,
                origin TEXT NOT NULL CHECK (origin IN ('local', 'imported'))
            );
            CREATE UNIQUE INDEX pieces_kind_number ON pieces (kind, number);
            PRAGMA user_version = 1;
            """, tx);
        tx.Commit();
    }

    public void Dispose() => _db.Dispose();

    public bool IsEmpty => Convert.ToInt64(Scalar(_db, "SELECT COUNT(*) FROM pieces;")) == 0;

    public int Count(PieceKind kind) =>
        Convert.ToInt32(Scalar(_db, "SELECT COUNT(*) FROM pieces WHERE kind = $kind;", null, ("$kind", Stored(kind))));

    /// <summary>Table of contents for one book, latest page first; <paramref name="search"/> matches title, task or text.</summary>
    public IReadOnlyList<Piece> Contents(PieceKind kind, string search = "")
    {
        var filter = string.IsNullOrWhiteSpace(search)
            ? ""
            : " AND (instr(lower(title), lower($q)) > 0 OR instr(lower(task), lower($q)) > 0 OR instr(lower(text), lower($q)) > 0)";
        return Query($"SELECT {Columns} FROM pieces WHERE kind = $kind{filter} ORDER BY number DESC;",
            ("$kind", Stored(kind)), ("$q", search.Trim()));
    }

    public Piece Get(string id) =>
        Query($"SELECT {Columns} FROM pieces WHERE id = $id;", ("$id", id)).SingleOrDefault()
        ?? throw new NotebookException("找不到这一页。");

    /// <summary>Turns to a fresh page at the end of the book.</summary>
    public Piece NewPage(PieceKind kind) => Insert(kind, "", "", "", revises: null);

    /// <summary>Saves the editable text of a draft. Unchanged text keeps its timestamp.</summary>
    public Piece Write(string id, string title, string task, string text)
    {
        var page = Get(id);
        if (page.IsHandedIn) throw new NotebookException("这一页已经交了，不能再改；可以「重写一稿」。");
        if (page.Title == title && page.Task == task && page.Text == text) return page;
        var now = Now();
        Exec(_db, "UPDATE pieces SET title = $title, task = $task, text = $text, updated_at = $now WHERE id = $id AND handed_in_at IS NULL;",
            null, ("$title", title), ("$task", task), ("$text", text), ("$now", now.ToUnixTimeMilliseconds()), ("$id", id));
        return page with { Title = title, Task = task, Text = text, UpdatedAt = now };
    }

    public Piece HandIn(string id)
    {
        var page = Get(id);
        if (page.IsHandedIn) throw new NotebookException("这一页已经交过了。");
        if (string.IsNullOrWhiteSpace(page.Task)) throw new NotebookException(page.Kind == PieceKind.Essay ? "先写上题目再交。" : "先放入中文原文再交。");
        if (string.IsNullOrWhiteSpace(page.Text)) throw new NotebookException("还没写正文，先写完再交。");
        var now = Now();
        Exec(_db, "UPDATE pieces SET handed_in_at = $now, updated_at = $now WHERE id = $id AND handed_in_at IS NULL;",
            null, ("$now", now.ToUnixTimeMilliseconds()), ("$id", id));
        return page with { HandedInAt = now, UpdatedAt = now };
    }

    /// <summary>
    /// Starts a new draft from a handed-in page. <paramref name="text"/> lets the caller carry accepted
    /// corrections; the original page is left exactly as it was.
    /// </summary>
    public Piece Rewrite(string id, string? text = null)
    {
        var page = Get(id);
        if (!page.IsHandedIn) throw new NotebookException("草稿可以直接改，交了之后才需要重写一稿。");
        return Insert(page.Kind, page.Title, page.Task, text ?? page.Text, revises: page.Id);
    }

    private Piece Insert(PieceKind kind, string title, string task, string text, string? revises)
    {
        var now = Now();
        var number = Convert.ToInt32(Scalar(_db, "SELECT COALESCE(MAX(number), 0) + 1 FROM pieces WHERE kind = $kind;", null,
            ("$kind", Stored(kind))));
        var page = new Piece(Guid.NewGuid().ToString("N"), kind, number, title, task, text, revises, now, now, null, false);
        InsertRow(page, null);
        return page;
    }

    /// <summary>Writes a complete row; used by imports, which keep their own timestamps.</summary>
    internal void InsertRow(Piece page, SqliteTransaction? tx) => Exec(_db, $"""
        INSERT INTO pieces ({Columns})
        VALUES ($id, $kind, $number, $title, $task, $text, $revises, $created, $updated, $handed, $origin);
        """, tx,
        ("$id", page.Id), ("$kind", Stored(page.Kind)), ("$number", page.Number), ("$title", page.Title),
        ("$task", page.Task), ("$text", page.Text), ("$revises", page.Revises), ("$created", page.CreatedAt.ToUnixTimeMilliseconds()),
        ("$updated", page.UpdatedAt.ToUnixTimeMilliseconds()), ("$handed", page.HandedInAt?.ToUnixTimeMilliseconds()),
        ("$origin", page.Imported ? "imported" : "local"));

    internal SqliteTransaction BeginTransaction() => _db.BeginTransaction();

    private DateTimeOffset Now() => DateTimeOffset.FromUnixTimeMilliseconds(_clock.GetUtcNow().ToUnixTimeMilliseconds());

    private static string Stored(PieceKind kind) => kind == PieceKind.Essay ? "essay" : "translation";

    private List<Piece> Query(string sql, params (string Name, object? Value)[] parameters)
    {
        using var command = _db.CreateCommand();
        command.CommandText = sql;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        using var reader = command.ExecuteReader();
        var pages = new List<Piece>();
        while (reader.Read())
        {
            pages.Add(new Piece(
                Id: reader.GetString(0),
                Kind: reader.GetString(1) == "essay" ? PieceKind.Essay : PieceKind.Translation,
                Number: reader.GetInt32(2),
                Title: reader.GetString(3),
                Task: reader.GetString(4),
                Text: reader.GetString(5),
                Revises: reader.IsDBNull(6) ? null : reader.GetString(6),
                CreatedAt: DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
                UpdatedAt: DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(8)),
                HandedInAt: reader.IsDBNull(9) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(9)),
                Imported: reader.GetString(10) == "imported"));
        }
        return pages;
    }

    private static object? Scalar(SqliteConnection db, string sql, SqliteTransaction? tx = null, params (string Name, object? Value)[] parameters)
    {
        using var command = db.CreateCommand();
        command.CommandText = sql;
        command.Transaction = tx;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        return command.ExecuteScalar();
    }

    private static void Exec(SqliteConnection db, string sql, SqliteTransaction? tx = null, params (string Name, object? Value)[] parameters)
    {
        using var command = db.CreateCommand();
        command.CommandText = sql;
        command.Transaction = tx;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        command.ExecuteNonQuery();
    }
}
