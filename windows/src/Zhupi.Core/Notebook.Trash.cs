namespace Zhupi.Core;

/// <summary>
/// The trash. Discarding only hides a page; everything hanging off it stays until it is purged from the trash,
/// which is the one place a page and its reviews, questions and hints are really deleted.
/// </summary>
public sealed partial class Notebook
{
    public void Discard(string id)
    {
        Get(id);
        Exec(_db, "UPDATE pieces SET discarded_at = $now WHERE id = $id AND discarded_at IS NULL;", null,
            ("$now", Now().ToUnixTimeMilliseconds()), ("$id", id));
    }

    public void Restore(string id)
    {
        Get(id);
        Exec(_db, "UPDATE pieces SET discarded_at = NULL WHERE id = $id;", null, ("$id", id));
    }

    /// <summary>Pages in the trash for one book, most recently discarded first.</summary>
    public IReadOnlyList<Piece> Trash(PieceKind kind) =>
        Query($"SELECT {Columns} FROM pieces WHERE kind = $kind AND discarded_at IS NOT NULL ORDER BY discarded_at DESC, number DESC;",
            ("$kind", Stored(kind)));

    /// <summary>
    /// Deletes a page from the trash for good, with its reviews, marks, questions, hints and paper link.
    /// Word cards collected from it stay in the word book without the link; later drafts that revised it stay
    /// and simply stop pointing back.
    /// </summary>
    public void Purge(string id)
    {
        Get(id);
        if (Scalar(_db, "SELECT discarded_at FROM pieces WHERE id = $id;", null, ("$id", id)) is DBNull)
        {
            throw new NotebookException("只有废纸篓里的页才能彻底删除。");
        }
        using var tx = _db.BeginTransaction();
        foreach (var sql in new[]
        {
            "DELETE FROM talks WHERE review_id IN (SELECT id FROM reviews WHERE piece_id = $id);",
            "DELETE FROM marks WHERE review_id IN (SELECT id FROM reviews WHERE piece_id = $id);",
            "DELETE FROM reviews WHERE piece_id = $id;",
            "DELETE FROM hints WHERE piece_id = $id;",
            "DELETE FROM paper_pages WHERE piece_id = $id;",
            "UPDATE words SET source_piece_id = NULL WHERE source_piece_id = $id;",
            "UPDATE pieces SET revises = NULL WHERE revises = $id;",
            "DELETE FROM pieces WHERE id = $id;",
        })
        {
            Exec(_db, sql, tx, ("$id", id));
        }
        tx.Commit();
    }
}
