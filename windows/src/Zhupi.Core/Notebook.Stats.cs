namespace Zhupi.Core;

/// <summary>A reviewed page's band, using the newest review of that page.</summary>
public sealed record BandPoint(PieceKind Kind, int Number, string Title, int Band, DateTimeOffset At);

/// <summary>One kind of must-fix mark and how often it came up, with the most recent example.</summary>
public sealed record Habit(string Kind, int Count, string Quote, string Fix);

public sealed record LookBack(
    int EssaysHandedIn,
    int TranslationsHandedIn,
    int ActiveDays,
    IReadOnlyList<BandPoint> Bands,
    IReadOnlyList<Habit> Habits,
    int Taken,
    int Left,
    int Undecided,
    int Words,
    int WordsDue,
    int WordReviewsThisWeek,
    int RememberedThisWeek);

public sealed partial class Notebook
{
    /// <summary>
    /// Everything the 复盘 page shows, read in one go. Habits only count must-fix marks from each page's newest
    /// review, so asking the teacher again does not count the same mistake twice.
    /// </summary>
    public LookBack LookBack(int activeDayWindow = 14, int bandCount = 12, int habitCount = 5)
    {
        var now = Now();
        long Count(string sql, params (string, object?)[] p) => Convert.ToInt64(Scalar(_db, sql, null, p));

        var essays = (int)Count("SELECT COUNT(*) FROM pieces WHERE kind = 'essay' AND handed_in_at IS NOT NULL;");
        var translations = (int)Count("SELECT COUNT(*) FROM pieces WHERE kind = 'translation' AND handed_in_at IS NOT NULL;");

        // Days on which any page was written or handed in, in local time, inside the window.
        var since = now.AddDays(-activeDayWindow).ToUnixTimeMilliseconds();
        var days = new HashSet<DateOnly>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = "SELECT updated_at FROM pieces WHERE updated_at >= $since UNION ALL SELECT handed_in_at FROM pieces WHERE handed_in_at >= $since;";
            command.Parameters.AddWithValue("$since", since);
            using var reader = command.ExecuteReader();
            while (reader.Read()) days.Add(DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(0)).ToLocalTime().Date));
        }

        const string newestReviews = """
            SELECT r.id, r.piece_id, r.band, r.created_at FROM reviews r
            WHERE r.created_at = (SELECT MAX(created_at) FROM reviews WHERE piece_id = r.piece_id)
            """;

        var bands = new List<BandPoint>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = $"""
                SELECT p.kind, p.number, p.title, p.task, n.band, n.created_at FROM ({newestReviews}) n
                JOIN pieces p ON p.id = n.piece_id ORDER BY n.created_at DESC LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$limit", bandCount);
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var title = reader.GetString(2).Trim();
                if (title.Length == 0) title = reader.GetString(3).Trim().Split('\n')[0];
                if (title.Length > 20) title = title[..20] + "…";
                bands.Add(new BandPoint(reader.GetString(0) == "essay" ? PieceKind.Essay : PieceKind.Translation, reader.GetInt32(1), title,
                    reader.GetInt32(4), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5))));
            }
        }
        bands.Reverse();

        var habits = new List<Habit>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = $"""
                SELECT m.kind, COUNT(*),
                       (SELECT m2.quote FROM marks m2 JOIN ({newestReviews}) n2 ON n2.id = m2.review_id
                        WHERE m2.kind = m.kind AND m2.severity = 'must' ORDER BY n2.created_at DESC, m2.number LIMIT 1),
                       (SELECT m2.fix FROM marks m2 JOIN ({newestReviews}) n2 ON n2.id = m2.review_id
                        WHERE m2.kind = m.kind AND m2.severity = 'must' ORDER BY n2.created_at DESC, m2.number LIMIT 1)
                FROM marks m JOIN ({newestReviews}) n ON n.id = m.review_id
                WHERE m.severity = 'must' AND m.kind <> ''
                GROUP BY m.kind ORDER BY COUNT(*) DESC, m.kind LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$limit", habitCount);
            using var reader = command.ExecuteReader();
            while (reader.Read()) habits.Add(new Habit(reader.GetString(0), reader.GetInt32(1), reader.GetString(2), reader.GetString(3)));
        }

        int Decided(string decision) => (int)Count(
            $"SELECT COUNT(*) FROM marks m JOIN ({newestReviews}) n ON n.id = m.review_id WHERE m.decision = $d;", ("$d", decision));

        var week = now.AddDays(-7).ToUnixTimeMilliseconds();
        return new LookBack(
            essays, translations, days.Count, bands, habits,
            Decided("taken"), Decided("left"), Decided("open"),
            (int)Count("SELECT COUNT(*) FROM words;"),
            (int)Count("SELECT COUNT(*) FROM words WHERE due_at <= $now;", ("$now", now.ToUnixTimeMilliseconds())),
            (int)Count("SELECT COUNT(*) FROM word_reviews WHERE reviewed_at >= $week;", ("$week", week)),
            (int)Count("SELECT COUNT(*) FROM word_reviews WHERE reviewed_at >= $week AND recall = 'remembered';", ("$week", week)));
    }
}
