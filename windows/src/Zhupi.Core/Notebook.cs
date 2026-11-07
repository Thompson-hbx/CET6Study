using Microsoft.Data.Sqlite;

namespace Zhupi.Core;

/// <summary>
/// The learner's notebook, one SQLite file. Each kind has its own page numbering, like separate exercise books.
/// Schema changes only ever add tables or columns.
/// </summary>
public sealed partial class Notebook : IDisposable
{
    public const int SchemaVersion = 6;

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
        if (version < 1) Exec(db, """
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
            """, tx);
        if (version < 2) Exec(db, """
            CREATE TABLE reviews (
                id TEXT PRIMARY KEY NOT NULL,
                piece_id TEXT NOT NULL REFERENCES pieces (id),
                created_at INTEGER NOT NULL,
                teacher TEXT NOT NULL,
                band INTEGER NOT NULL,
                verdict TEXT NOT NULL,
                dimensions TEXT NOT NULL
            );
            CREATE INDEX reviews_piece ON reviews (piece_id, created_at);
            CREATE TABLE marks (
                review_id TEXT NOT NULL REFERENCES reviews (id),
                number INTEGER NOT NULL,
                quote TEXT NOT NULL,
                fix TEXT NOT NULL,
                why TEXT NOT NULL,
                severity TEXT NOT NULL CHECK (severity IN ('must', 'maybe')),
                kind TEXT NOT NULL,
                start INTEGER,
                decision TEXT NOT NULL CHECK (decision IN ('open', 'taken', 'left')),
                PRIMARY KEY (review_id, number)
            );
            """, tx);
        if (version < 3) Exec(db, """
            CREATE TABLE talks (
                id TEXT PRIMARY KEY NOT NULL,
                review_id TEXT NOT NULL REFERENCES reviews (id),
                mark_number INTEGER,
                question TEXT NOT NULL,
                answer TEXT NOT NULL,
                teacher TEXT NOT NULL,
                asked_at INTEGER NOT NULL
            );
            CREATE INDEX talks_review ON talks (review_id, asked_at);
            """, tx);
        // Hints are tied to the sentence text, so editing the source later does not show a hint for a different sentence.
        if (version < 4) Exec(db, """
            CREATE TABLE hints (
                id TEXT PRIMARY KEY NOT NULL,
                piece_id TEXT NOT NULL REFERENCES pieces (id),
                sentence INTEGER NOT NULL CHECK (sentence > 0),
                sentence_text TEXT NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('words', 'frame')),
                text TEXT NOT NULL,
                teacher TEXT NOT NULL,
                given_at INTEGER NOT NULL
            );
            CREATE INDEX hints_piece ON hints (piece_id, sentence, given_at);
            """, tx);
        if (version < 5) Exec(db, """
            CREATE TABLE words (
                id TEXT PRIMARY KEY NOT NULL,
                front TEXT NOT NULL,
                back TEXT NOT NULL,
                note TEXT NOT NULL,
                source_piece_id TEXT REFERENCES pieces (id),
                box INTEGER NOT NULL CHECK (box BETWEEN 1 AND 6),
                due_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                reviews INTEGER NOT NULL DEFAULT 0
            );
            CREATE UNIQUE INDEX words_front_back ON words (front, back);
            CREATE INDEX words_due ON words (due_at);
            CREATE TABLE word_reviews (
                word_id TEXT NOT NULL REFERENCES words (id),
                reviewed_at INTEGER NOT NULL,
                recall TEXT NOT NULL CHECK (recall IN ('forgot', 'fuzzy', 'remembered')),
                box_after INTEGER NOT NULL
            );
            CREATE INDEX word_reviews_time ON word_reviews (reviewed_at);
            """, tx);
        // Papers come from content packs. The link table keeps pages free of paper columns, so pages
        // written by hand and pages started from a paper stay the same kind of row.
        if (version < 6) Exec(db, """
            CREATE TABLE papers (
                id TEXT PRIMARY KEY NOT NULL,
                exam_date TEXT NOT NULL,
                set_code INTEGER NOT NULL,
                revision INTEGER NOT NULL CHECK (revision > 0),
                source_name TEXT NOT NULL,
                source_url TEXT NOT NULL,
                license TEXT NOT NULL CHECK (license IN ('personal-use', 'redistributable', 'original')),
                reviewed INTEGER NOT NULL CHECK (reviewed IN (0, 1)),
                writing_prompt TEXT NOT NULL,
                writing_instructions TEXT NOT NULL,
                chinese_text TEXT NOT NULL,
                reference_translation TEXT NOT NULL,
                reference_translation_source TEXT NOT NULL,
                reference_essay TEXT NOT NULL,
                reference_essay_source TEXT NOT NULL,
                imported_at INTEGER NOT NULL
            );
            CREATE TABLE paper_pages (
                piece_id TEXT PRIMARY KEY NOT NULL REFERENCES pieces (id),
                paper_id TEXT NOT NULL REFERENCES papers (id)
            );
            CREATE INDEX paper_pages_paper ON paper_pages (paper_id);
            """, tx);
        Exec(db, $"PRAGMA user_version = {SchemaVersion};", tx);
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
        // A rewrite answers the same paper, so it keeps the link to it.
        using var tx = _db.BeginTransaction();
        var rewrite = Insert(page.Kind, page.Title, page.Task, text ?? page.Text, revises: page.Id, tx);
        Exec(_db, "INSERT INTO paper_pages (piece_id, paper_id) SELECT $new, paper_id FROM paper_pages WHERE piece_id = $old;",
            tx, ("$new", rewrite.Id), ("$old", page.Id));
        tx.Commit();
        return rewrite;
    }

    private Piece Insert(PieceKind kind, string title, string task, string text, string? revises, SqliteTransaction? tx = null)
    {
        var now = Now();
        var number = Convert.ToInt32(Scalar(_db, "SELECT COALESCE(MAX(number), 0) + 1 FROM pieces WHERE kind = $kind;", tx,
            ("$kind", Stored(kind))));
        var page = new Piece(Guid.NewGuid().ToString("N"), kind, number, title, task, text, revises, now, now, null, false);
        InsertRow(page, tx);
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
