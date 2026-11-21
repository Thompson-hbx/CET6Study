namespace Inkwell.Core;

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

        // Pages in the trash are left out everywhere here, so test pages thrown away stop counting.
        int essays = 0, translations = 0;
        using (var command = _db.CreateCommand())
        {
            command.CommandText = """
                SELECT COALESCE(SUM(CASE WHEN kind = 'essay' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN kind = 'translation' THEN 1 ELSE 0 END), 0)
                FROM pieces
                WHERE handed_in_at IS NOT NULL AND discarded_at IS NULL;
                """;
            using var reader = command.ExecuteReader();
            if (reader.Read())
            {
                essays = reader.GetInt32(0);
                translations = reader.GetInt32(1);
            }
        }

        // Local days, today and the window's other days back, on which the learner did anything recorded: started,
        // edited or handed in a page, had it marked, asked, took a hint or reviewed words. A page keeps only its last
        // edit time, so the other records fill in days that a later edit would otherwise hide.
        var zone = _clock.LocalTimeZone;
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now, zone).Date);
        var since = WordSchedule.StartOfDay(today.AddDays(1 - activeDayWindow), zone).ToUnixTimeMilliseconds();
        var days = new HashSet<DateOnly>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = """
                SELECT at FROM (
                SELECT created_at AS at FROM pieces WHERE discarded_at IS NULL
                UNION ALL SELECT updated_at FROM pieces WHERE discarded_at IS NULL
                UNION ALL SELECT handed_in_at FROM pieces WHERE handed_in_at IS NOT NULL AND discarded_at IS NULL
                UNION ALL SELECT r.created_at FROM reviews r JOIN pieces p ON p.id = r.piece_id WHERE p.discarded_at IS NULL
                UNION ALL SELECT t.asked_at FROM talks t JOIN reviews r ON r.id = t.review_id JOIN pieces p ON p.id = r.piece_id WHERE p.discarded_at IS NULL
                UNION ALL SELECT h.given_at FROM hints h JOIN pieces p ON p.id = h.piece_id WHERE p.discarded_at IS NULL
                UNION ALL SELECT reviewed_at FROM word_reviews
                ) WHERE at >= $since;
                """;
            command.Parameters.AddWithValue("$since", since);
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                days.Add(DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(0)), zone).Date));
            }
        }

        const string newestCte = """
            WITH newest AS (
                SELECT id, piece_id, band, created_at, rev_rowid
                FROM (
                    SELECT r.id,
                           r.piece_id,
                           r.band,
                           r.created_at,
                           r.rowid AS rev_rowid,
                           ROW_NUMBER() OVER (PARTITION BY r.piece_id ORDER BY r.created_at DESC, r.rowid DESC) AS rn
                    FROM reviews r
                    JOIN pieces p ON p.id = r.piece_id
                    WHERE p.discarded_at IS NULL
                )
                WHERE rn = 1
            )
            """;

        var bands = new List<BandPoint>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = $"""
                {newestCte}
                SELECT p.kind, p.number, p.title, p.task, n.band, n.created_at
                FROM newest n
                JOIN pieces p ON p.id = n.piece_id
                ORDER BY n.created_at DESC, n.rev_rowid DESC
                LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$limit", bandCount);
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var title = reader.GetString(2).Trim();
                if (title.Length == 0)
                {
                    var task = reader.GetString(3).Trim();
                    var eol = task.IndexOfAny(['\r', '\n']);
                    title = (eol >= 0 ? task[..eol] : task).Trim();
                }
                title = TruncateTitle(title, 20);
                bands.Add(new BandPoint(reader.GetString(0) == "essay" ? PieceKind.Essay : PieceKind.Translation, reader.GetInt32(1), title,
                    reader.GetInt32(4), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5))));
            }
        }
        bands.Reverse();

        var habits = new List<Habit>();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = $"""
                {newestCte},
                must_marks AS (
                    SELECT m.kind,
                           m.quote,
                           m.fix,
                           COUNT(*) OVER (PARTITION BY m.kind) AS cnt,
                           ROW_NUMBER() OVER (PARTITION BY m.kind ORDER BY n.created_at DESC, n.rev_rowid DESC, m.number) AS mark_rn
                    FROM marks m
                    JOIN newest n ON n.id = m.review_id
                    WHERE m.severity = 'must' AND m.kind <> ''
                )
                SELECT kind, cnt, quote, fix
                FROM must_marks
                WHERE mark_rn = 1
                ORDER BY cnt DESC, kind
                LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$limit", habitCount);
            using var reader = command.ExecuteReader();
            while (reader.Read()) habits.Add(new Habit(reader.GetString(0), reader.GetInt32(1), reader.GetString(2), reader.GetString(3)));
        }

        int taken = 0, left = 0, undecided = 0;
        using (var command = _db.CreateCommand())
        {
            command.CommandText = $"""
                {newestCte}
                SELECT COALESCE(SUM(CASE WHEN m.decision = 'taken' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN m.decision = 'left' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN m.decision = 'open' THEN 1 ELSE 0 END), 0)
                FROM marks m
                JOIN newest n ON n.id = m.review_id;
                """;
            using var reader = command.ExecuteReader();
            if (reader.Read())
            {
                taken = reader.GetInt32(0);
                left = reader.GetInt32(1);
                undecided = reader.GetInt32(2);
            }
        }

        int words = 0, wordsDue = 0;
        using (var command = _db.CreateCommand())
        {
            command.CommandText = """
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN due_at <= $now THEN 1 ELSE 0 END), 0)
                FROM words;
                """;
            command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            using var reader = command.ExecuteReader();
            if (reader.Read())
            {
                words = reader.GetInt32(0);
                wordsDue = reader.GetInt32(1);
            }
        }

        int wordReviewsThisWeek = 0, rememberedThisWeek = 0;
        var week = now.AddDays(-7).ToUnixTimeMilliseconds();
        using (var command = _db.CreateCommand())
        {
            command.CommandText = """
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN recall = 'remembered' THEN 1 ELSE 0 END), 0)
                FROM word_reviews
                WHERE reviewed_at >= $week;
                """;
            command.Parameters.AddWithValue("$week", week);
            using var reader = command.ExecuteReader();
            if (reader.Read())
            {
                wordReviewsThisWeek = reader.GetInt32(0);
                rememberedThisWeek = reader.GetInt32(1);
            }
        }

        return new LookBack(
            essays, translations, days.Count, bands, habits,
            taken, left, undecided,
            words, wordsDue, wordReviewsThisWeek, rememberedThisWeek);
    }

    private static string TruncateTitle(string value, int maxChars)
    {
        if (value.Length <= maxChars) return value;
        var cut = maxChars;
        if (cut > 0 && char.IsHighSurrogate(value[cut - 1])) cut--;
        return value[..cut] + "…";
    }
}
