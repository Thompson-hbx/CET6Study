using System.Text.Json;
using Zhupi.Core.Teacher;

namespace Zhupi.Core;

/// <summary>One stored review of a handed-in page, with its marks placed on that page's text.</summary>
public sealed record StoredReview(
    string Id,
    string PieceId,
    DateTimeOffset CreatedAt,
    string Teacher,
    int Band,
    string Verdict,
    IReadOnlyList<Dimension> Dimensions,
    IReadOnlyList<PlacedMark> Marks)
{
    public int TakenCount => Marks.Count(m => m.Decision == Decision.Taken && m.IsPlaced);
}

public sealed partial class Notebook
{
    /// <summary>Keeps a teacher's marking of a handed-in page; each review is kept, the newest is shown.</summary>
    public StoredReview SaveReview(string pieceId, Marking marking, string teacher)
    {
        var page = Get(pieceId);
        if (!page.IsHandedIn) throw new NotebookException("交了才能请老师批改。");
        var starts = Placement.Locate(page.Text, marking.Marks);
        var review = new StoredReview(
            Guid.NewGuid().ToString("N"), pieceId, Now(), teacher, marking.Band, marking.Verdict, marking.Dimensions,
            marking.Marks.Select((m, i) => new PlacedMark(m, starts[i], Decision.Open)).ToList());
        using var tx = _db.BeginTransaction();
        Exec(_db, """
            INSERT INTO reviews (id, piece_id, created_at, teacher, band, verdict, dimensions)
            VALUES ($id, $piece, $at, $teacher, $band, $verdict, $dims);
            """, tx,
            ("$id", review.Id), ("$piece", pieceId), ("$at", review.CreatedAt.ToUnixTimeMilliseconds()), ("$teacher", teacher),
            ("$band", review.Band), ("$verdict", review.Verdict), ("$dims", JsonSerializer.Serialize(review.Dimensions)));
        foreach (var placed in review.Marks)
        {
            var m = placed.Mark;
            Exec(_db, """
                INSERT INTO marks (review_id, number, quote, fix, why, severity, kind, start, decision)
                VALUES ($review, $n, $quote, $fix, $why, $severity, $kind, $start, 'open');
                """, tx,
                ("$review", review.Id), ("$n", m.Number), ("$quote", m.Quote), ("$fix", m.Fix), ("$why", m.Why),
                ("$severity", m.Severity == Severity.Must ? "must" : "maybe"), ("$kind", m.Kind), ("$start", placed.Start));
        }
        tx.Commit();
        return review;
    }

    public StoredReview? LatestReview(string pieceId)
    {
        using var command = _db.CreateCommand();
        command.CommandText = """
            SELECT id, created_at, teacher, band, verdict, dimensions FROM reviews
            WHERE piece_id = $piece ORDER BY created_at DESC, rowid DESC LIMIT 1;
            """;
        command.Parameters.AddWithValue("$piece", pieceId);
        string id, teacher, verdict, dims;
        long at;
        int band;
        using (var reader = command.ExecuteReader())
        {
            if (!reader.Read()) return null;
            (id, at, teacher, band, verdict, dims) =
                (reader.GetString(0), reader.GetInt64(1), reader.GetString(2), reader.GetInt32(3), reader.GetString(4), reader.GetString(5));
        }
        return new StoredReview(id, pieceId, DateTimeOffset.FromUnixTimeMilliseconds(at), teacher, band, verdict,
            JsonSerializer.Deserialize<List<Dimension>>(dims) ?? [], ReadMarks(id));
    }

    /// <summary>Records the learner's choice on one mark.</summary>
    public void Decide(string reviewId, int number, Decision decision)
    {
        var stored = decision switch
        {
            Decision.Taken => "taken",
            Decision.Left => "left",
            _ => "open",
        };
        using var command = _db.CreateCommand();
        command.CommandText = "UPDATE marks SET decision = $d WHERE review_id = $review AND number = $n;";
        command.Parameters.AddWithValue("$d", stored);
        command.Parameters.AddWithValue("$review", reviewId);
        command.Parameters.AddWithValue("$n", number);
        if (command.ExecuteNonQuery() != 1) throw new NotebookException("这条批注找不到了。");
    }

    /// <summary>A new draft of a handed-in page with every taken fix of its newest review written in.</summary>
    public Piece RewriteWithTaken(string pieceId)
    {
        var page = Get(pieceId);
        var review = LatestReview(pieceId);
        return Rewrite(pieceId, review is null ? page.Text : Placement.Apply(page.Text, review.Marks));
    }

    private List<PlacedMark> ReadMarks(string reviewId)
    {
        using var command = _db.CreateCommand();
        command.CommandText = """
            SELECT number, quote, fix, why, severity, kind, start, decision FROM marks
            WHERE review_id = $review ORDER BY number;
            """;
        command.Parameters.AddWithValue("$review", reviewId);
        using var reader = command.ExecuteReader();
        var marks = new List<PlacedMark>();
        while (reader.Read())
        {
            var mark = new Mark(reader.GetInt32(0), reader.GetString(1), reader.GetString(2), reader.GetString(3),
                reader.GetString(4) == "must" ? Severity.Must : Severity.Maybe, reader.GetString(5));
            var decision = reader.GetString(7) switch
            {
                "taken" => Decision.Taken,
                "left" => Decision.Left,
                _ => Decision.Open,
            };
            marks.Add(new PlacedMark(mark, reader.IsDBNull(6) ? null : reader.GetInt32(6), decision));
        }
        return marks;
    }
}
