using Zhupi.Core.Teacher;

namespace Zhupi.Core;

public sealed partial class Notebook
{
    /// <summary>Questions and answers on one thread of a review: a single mark, or the whole page when <paramref name="markNumber"/> is null.</summary>
    public IReadOnlyList<Talk> Talks(string reviewId, int? markNumber)
    {
        using var command = _db.CreateCommand();
        command.CommandText = markNumber is null
            ? "SELECT mark_number, question, answer, teacher, asked_at FROM talks WHERE review_id = $review AND mark_number IS NULL ORDER BY asked_at, rowid;"
            : "SELECT mark_number, question, answer, teacher, asked_at FROM talks WHERE review_id = $review AND mark_number = $mark ORDER BY asked_at, rowid;";
        command.Parameters.AddWithValue("$review", reviewId);
        if (markNumber is { } n) command.Parameters.AddWithValue("$mark", n);
        using var reader = command.ExecuteReader();
        var talks = new List<Talk>();
        while (reader.Read())
        {
            talks.Add(new Talk(reader.IsDBNull(0) ? null : reader.GetInt32(0), reader.GetString(1), reader.GetString(2),
                reader.GetString(3), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(4))));
        }
        return talks;
    }

    /// <summary>Keeps a question together with its complete answer; nothing is written for an unanswered question.</summary>
    public Talk SaveTalk(string reviewId, int? markNumber, string question, string answer, string teacher)
    {
        if (string.IsNullOrWhiteSpace(answer)) throw new NotebookException("老师没有回答，什么都没记。");
        using (var check = _db.CreateCommand())
        {
            check.CommandText = markNumber is null
                ? "SELECT COUNT(*) FROM reviews WHERE id = $review;"
                : "SELECT COUNT(*) FROM marks WHERE review_id = $review AND number = $mark;";
            check.Parameters.AddWithValue("$review", reviewId);
            if (markNumber is { } n) check.Parameters.AddWithValue("$mark", n);
            if (Convert.ToInt64(check.ExecuteScalar()) == 0) throw new NotebookException("这条批注找不到了，问答没有记下。");
        }
        var talk = new Talk(markNumber, question.Trim(), answer.Trim(), teacher, Now());
        Exec(_db, """
            INSERT INTO talks (id, review_id, mark_number, question, answer, teacher, asked_at)
            VALUES ($id, $review, $mark, $q, $a, $teacher, $at);
            """, null,
            ("$id", Guid.NewGuid().ToString("N")), ("$review", reviewId), ("$mark", markNumber), ("$q", talk.Question),
            ("$a", talk.Answer), ("$teacher", teacher), ("$at", talk.AskedAt.ToUnixTimeMilliseconds()));
        return talk;
    }
}
