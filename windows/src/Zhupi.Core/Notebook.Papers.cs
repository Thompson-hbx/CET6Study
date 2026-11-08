using Microsoft.Data.Sqlite;

namespace Zhupi.Core;

/// <summary>What an import changed; papers that were left out carry their reason.</summary>
public sealed record PackImport(int Added, int Updated, int Unchanged, IReadOnlyList<PackSkip> Skipped);

/// <summary>How far one part (essay or translation) of a paper has got in the notebook.</summary>
public sealed record PartProgress(int Pages, int HandedIn, string? LatestId, int? LatestNumber, bool LatestIsDraft);

public sealed record PaperOnShelf(ExamPaper Paper, PartProgress Essay, PartProgress Translation);

public sealed partial class Notebook
{
    private const string PaperColumns = """
        id, exam_date, set_code, revision, source_name, source_url, license, reviewed, writing_prompt, writing_instructions,
        chinese_text, reference_translation, reference_translation_source, reference_essay, reference_essay_source
        """;

    /// <summary>
    /// Stores the papers of a content pack. A higher revision replaces the stored one; the same revision with
    /// different content is a conflict and is left alone, because only the preparer can say which is right.
    /// </summary>
    public PackImport ImportPapers(ExamPack pack)
    {
        int added = 0, updated = 0, unchanged = 0;
        var skipped = new List<PackSkip>(pack.Skipped);
        using var tx = _db.BeginTransaction();
        foreach (var paper in pack.Papers)
        {
            var stored = FindPaper(paper.Id, tx);
            if (stored is null)
            {
                WritePaper(paper, tx);
                added++;
            }
            else if (stored == paper)
            {
                unchanged++;
            }
            else if (paper.Revision > stored.Revision)
            {
                WritePaper(paper, tx);
                updated++;
            }
            else
            {
                skipped.Add(new PackSkip(paper.Id, paper.Revision < stored.Revision
                    ? $"本子里已经是更新的修订（{stored.Revision}），没有退回旧的"
                    : "修订号相同但内容不同，没有覆盖；改过内容请把 revision 加一"));
            }
        }
        tx.Commit();
        return new PackImport(added, updated, unchanged, skipped);
    }

    /// <summary>Every stored paper, newest exam first, with how far each part has got.</summary>
    public IReadOnlyList<PaperOnShelf> Shelf()
    {
        var papers = QueryPapers($"SELECT {PaperColumns} FROM papers ORDER BY exam_date DESC, set_code;", null);
        var progress = new Dictionary<(string, PieceKind), PartProgress>();
        using (var command = _db.CreateCommand())
        {
            // Pages per paper and kind, plus the newest page so the shelf can open it.
            command.CommandText = """
                SELECT pp.paper_id, p.kind, COUNT(*), SUM(p.handed_in_at IS NOT NULL),
                       (SELECT p2.id FROM paper_pages pp2 JOIN pieces p2 ON p2.id = pp2.piece_id
                        WHERE pp2.paper_id = pp.paper_id AND p2.kind = p.kind AND p2.discarded_at IS NULL ORDER BY p2.number DESC LIMIT 1)
                FROM paper_pages pp JOIN pieces p ON p.id = pp.piece_id
                WHERE p.discarded_at IS NULL
                GROUP BY pp.paper_id, p.kind;
                """;
            using var reader = command.ExecuteReader();
            var rows = new List<(string Paper, PieceKind Kind, int Pages, int HandedIn, string Latest)>();
            while (reader.Read())
            {
                rows.Add((reader.GetString(0), reader.GetString(1) == "essay" ? PieceKind.Essay : PieceKind.Translation,
                    reader.GetInt32(2), reader.GetInt32(3), reader.GetString(4)));
            }
            reader.Close();
            foreach (var row in rows)
            {
                var latest = Get(row.Latest);
                progress[(row.Paper, row.Kind)] = new PartProgress(row.Pages, row.HandedIn, latest.Id, latest.Number, !latest.IsHandedIn);
            }
        }
        var none = new PartProgress(0, 0, null, null, false);
        return papers.Select(p => new PaperOnShelf(p,
            progress.GetValueOrDefault((p.Id, PieceKind.Essay), none),
            progress.GetValueOrDefault((p.Id, PieceKind.Translation), none))).ToList();
    }

    public ExamPaper Paper(string id) => FindPaper(id, null) ?? throw new NotebookException("找不到这套卷子。");

    /// <summary>The paper a page was started from, if any.</summary>
    public ExamPaper? PaperOf(string pieceId) => QueryPapers($"""
        SELECT {PaperColumns} FROM papers WHERE id = (SELECT paper_id FROM paper_pages WHERE piece_id = $piece);
        """, null, ("$piece", pieceId)).SingleOrDefault();

    /// <summary>
    /// Opens the draft already started for this part, or turns to a new page with the paper's task on it.
    /// Handed-in pages are not reopened; another attempt starts a fresh page.
    /// </summary>
    public Piece StartFromPaper(string paperId, PieceKind kind)
    {
        var paper = Paper(paperId);
        var task = paper.Task(kind);
        if (task.Length == 0) throw new NotebookException(kind == PieceKind.Essay ? "这套卷子没有作文题。" : "这套卷子没有翻译原文。");
        var draft = Scalar(_db, """
            SELECT p.id FROM paper_pages pp JOIN pieces p ON p.id = pp.piece_id
            WHERE pp.paper_id = $paper AND p.kind = $kind AND p.handed_in_at IS NULL AND p.discarded_at IS NULL
            ORDER BY p.number DESC LIMIT 1;
            """, null, ("$paper", paperId), ("$kind", Stored(kind)));
        if (draft is string id) return Get(id);
        using var tx = _db.BeginTransaction();
        var page = Insert(kind, paper.PageTitle(kind), task, "", revises: null, tx);
        Link(page.Id, paperId, tx);
        tx.Commit();
        return page;
    }

    private void Link(string pieceId, string paperId, SqliteTransaction? tx) =>
        Exec(_db, "INSERT INTO paper_pages (piece_id, paper_id) VALUES ($piece, $paper);", tx, ("$piece", pieceId), ("$paper", paperId));

    private ExamPaper? FindPaper(string id, SqliteTransaction? tx) =>
        QueryPapers($"SELECT {PaperColumns} FROM papers WHERE id = $id;", tx, ("$id", id)).SingleOrDefault();

    // An upsert rather than REPLACE: REPLACE deletes the row first, which pages linked to the paper forbid.
    private void WritePaper(ExamPaper paper, SqliteTransaction tx) =>
        Exec(_db, $"""
            INSERT INTO papers ({PaperColumns}, imported_at)
            VALUES ($id, $date, $set, $revision, $sourceName, $sourceUrl, $license, $reviewed, $prompt, $instructions,
                    $chinese, $refTranslation, $refTranslationSource, $refEssay, $refEssaySource, $now)
            ON CONFLICT (id) DO UPDATE SET
                exam_date = excluded.exam_date, set_code = excluded.set_code, revision = excluded.revision,
                source_name = excluded.source_name, source_url = excluded.source_url, license = excluded.license,
                reviewed = excluded.reviewed, writing_prompt = excluded.writing_prompt,
                writing_instructions = excluded.writing_instructions, chinese_text = excluded.chinese_text,
                reference_translation = excluded.reference_translation,
                reference_translation_source = excluded.reference_translation_source,
                reference_essay = excluded.reference_essay, reference_essay_source = excluded.reference_essay_source,
                imported_at = excluded.imported_at;
            """, tx,
            ("$id", paper.Id), ("$date", paper.ExamDate.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)),
            ("$set", paper.Set), ("$revision", paper.Revision), ("$sourceName", paper.SourceName), ("$sourceUrl", paper.SourceUrl),
            ("$license", paper.License), ("$reviewed", paper.Reviewed ? 1 : 0), ("$prompt", paper.WritingPrompt),
            ("$instructions", paper.WritingInstructions), ("$chinese", paper.ChineseText),
            ("$refTranslation", paper.ReferenceTranslation), ("$refTranslationSource", paper.ReferenceTranslationSource),
            ("$refEssay", paper.ReferenceEssay), ("$refEssaySource", paper.ReferenceEssaySource),
            ("$now", Now().ToUnixTimeMilliseconds()));

    private List<ExamPaper> QueryPapers(string sql, SqliteTransaction? tx, params (string Name, object? Value)[] parameters)
    {
        using var command = _db.CreateCommand();
        command.CommandText = sql;
        command.Transaction = tx;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        using var reader = command.ExecuteReader();
        var papers = new List<ExamPaper>();
        while (reader.Read())
        {
            papers.Add(new ExamPaper(
                Id: reader.GetString(0),
                ExamDate: DateOnly.ParseExact(reader.GetString(1), "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture),
                Set: reader.GetInt32(2),
                Revision: reader.GetInt32(3),
                SourceName: reader.GetString(4),
                SourceUrl: reader.GetString(5),
                License: reader.GetString(6),
                Reviewed: reader.GetInt32(7) == 1,
                WritingPrompt: reader.GetString(8),
                WritingInstructions: reader.GetString(9),
                ChineseText: reader.GetString(10),
                ReferenceTranslation: reader.GetString(11),
                ReferenceTranslationSource: reader.GetString(12),
                ReferenceEssay: reader.GetString(13),
                ReferenceEssaySource: reader.GetString(14)));
        }
        return papers;
    }
}
