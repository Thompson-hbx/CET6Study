using Microsoft.Data.Sqlite;

namespace Zhupi.Core;

public sealed partial class Notebook
{
    private const string WordColumns = "id, front, back, note, source_piece_id, box, due_at, created_at, reviews";

    /// <summary>Adds a card that is due right away. The same front and back cannot be added twice.</summary>
    public WordCard AddWord(string front, string back, string note, string? sourcePieceId)
    {
        front = front.Trim();
        back = back.Trim();
        if (front.Length == 0 || back.Length == 0) throw new NotebookException("正面和背面都要写上。");
        if (front.Length > 300 || back.Length > 300 || note.Length > 500) throw new NotebookException("这一条太长了，单词本里写短一点。");
        var now = Now();
        var card = new WordCard(Guid.NewGuid().ToString("N"), front, back, note.Trim(), sourcePieceId, WordSchedule.FirstBox, now, now, 0);
        try
        {
            Exec(_db, $"""
                INSERT INTO words ({WordColumns}) VALUES ($id, $front, $back, $note, $source, $box, $due, $created, 0);
                """, null,
                ("$id", card.Id), ("$front", front), ("$back", back), ("$note", card.Note), ("$source", sourcePieceId),
                ("$box", card.Box), ("$due", now.ToUnixTimeMilliseconds()), ("$created", now.ToUnixTimeMilliseconds()));
        }
        catch (SqliteException e) when (e.SqliteErrorCode == 19)
        {
            throw new NotebookException("单词本里已经有这一条了。");
        }
        return card;
    }

    /// <summary>All cards, the ones due soonest first; <paramref name="search"/> matches front, back or note.</summary>
    public IReadOnlyList<WordCard> Words(string search = "") => QueryWords(
        $"SELECT {WordColumns} FROM words" +
        (string.IsNullOrWhiteSpace(search) ? "" : " WHERE instr(lower(front), lower($q)) > 0 OR instr(lower(back), lower($q)) > 0 OR instr(lower(note), lower($q)) > 0") +
        " ORDER BY due_at, created_at;", ("$q", search.Trim()));

    public IReadOnlyList<WordCard> DueWords() => QueryWords(
        $"SELECT {WordColumns} FROM words WHERE due_at <= $now ORDER BY due_at, created_at;",
        ("$now", Now().ToUnixTimeMilliseconds()));

    /// <summary>Moves the card between boxes and keeps a log line for the review page.</summary>
    public WordCard ReviewWord(string id, Recall recall)
    {
        var card = QueryWords($"SELECT {WordColumns} FROM words WHERE id = $id;", ("$id", id)).SingleOrDefault()
            ?? throw new NotebookException("这张卡片找不到了。");
        var now = Now();
        var (box, due) = WordSchedule.Next(card.Box, recall, now);
        using var tx = _db.BeginTransaction();
        Exec(_db, "UPDATE words SET box = $box, due_at = $due, reviews = reviews + 1 WHERE id = $id;", tx,
            ("$box", box), ("$due", due.ToUnixTimeMilliseconds()), ("$id", id));
        Exec(_db, "INSERT INTO word_reviews (word_id, reviewed_at, recall, box_after) VALUES ($id, $at, $recall, $box);", tx,
            ("$id", id), ("$at", now.ToUnixTimeMilliseconds()),
            ("$recall", recall switch { Recall.Forgot => "forgot", Recall.Fuzzy => "fuzzy", _ => "remembered" }), ("$box", box));
        tx.Commit();
        return card with { Box = box, DueAt = due, Reviews = card.Reviews + 1 };
    }

    /// <summary>Removes a card the learner no longer wants, together with its review log.</summary>
    public void RemoveWord(string id)
    {
        using var tx = _db.BeginTransaction();
        Exec(_db, "DELETE FROM word_reviews WHERE word_id = $id;", tx, ("$id", id));
        Exec(_db, "DELETE FROM words WHERE id = $id;", tx, ("$id", id));
        tx.Commit();
    }

    private List<WordCard> QueryWords(string sql, params (string Name, object? Value)[] parameters)
    {
        using var command = _db.CreateCommand();
        command.CommandText = sql;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        using var reader = command.ExecuteReader();
        var cards = new List<WordCard>();
        while (reader.Read())
        {
            cards.Add(new WordCard(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3),
                reader.IsDBNull(4) ? null : reader.GetString(4), reader.GetInt32(5),
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(6)), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
                reader.GetInt32(8)));
        }
        return cards;
    }
}
