using Zhupi.Core.Teacher;

namespace Zhupi.Core;

public sealed partial class Notebook
{
    /// <summary>
    /// The newest hint of each kind for a sentence whose text still matches; after the source is edited,
    /// hints given for the old wording are not shown against the new one.
    /// </summary>
    public IReadOnlyList<SentenceHint> Hints(string pieceId, int sentence, string sentenceText)
    {
        using var command = _db.CreateCommand();
        command.CommandText = """
            SELECT kind, text, teacher, given_at FROM hints
            WHERE piece_id = $piece AND sentence = $n AND sentence_text = $text
            ORDER BY given_at DESC, rowid DESC;
            """;
        command.Parameters.AddWithValue("$piece", pieceId);
        command.Parameters.AddWithValue("$n", sentence);
        command.Parameters.AddWithValue("$text", sentenceText);
        using var reader = command.ExecuteReader();
        var newest = new Dictionary<HintKind, SentenceHint>();
        while (reader.Read())
        {
            var kind = reader.GetString(0) == "words" ? HintKind.Words : HintKind.Frame;
            newest.TryAdd(kind, new SentenceHint(sentence, kind, reader.GetString(1), reader.GetString(2),
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(3))));
        }
        return newest.Values.OrderBy(h => h.Kind).ToList();
    }

    public SentenceHint SaveHint(string pieceId, int sentence, string sentenceText, HintKind kind, string text, string teacher)
    {
        var page = Get(pieceId);
        if (page.Kind != PieceKind.Translation) throw new NotebookException("只有翻译页有逐句提示。");
        if (string.IsNullOrWhiteSpace(text)) throw new NotebookException("老师没有给出提示，什么都没记。");
        var hint = new SentenceHint(sentence, kind, text.Trim(), teacher, Now());
        Exec(_db, """
            INSERT INTO hints (id, piece_id, sentence, sentence_text, kind, text, teacher, given_at)
            VALUES ($id, $piece, $n, $sentence, $kind, $text, $teacher, $at);
            """, null,
            ("$id", Guid.NewGuid().ToString("N")), ("$piece", pieceId), ("$n", sentence), ("$sentence", sentenceText),
            ("$kind", kind == HintKind.Words ? "words" : "frame"), ("$text", hint.Text), ("$teacher", teacher),
            ("$at", hint.GivenAt.ToUnixTimeMilliseconds()));
        return hint;
    }
}
