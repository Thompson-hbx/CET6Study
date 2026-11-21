namespace Inkwell.Core;

public enum Recall
{
    Forgot,
    Fuzzy,
    Remembered,
}

/// <summary>One card in the word book: a prompt on the front, the English to recall on the back.</summary>
public sealed record WordCard(
    string Id,
    string Front,
    string Back,
    string Note,
    string? SourcePieceId,
    int Box,
    DateTimeOffset DueAt,
    DateTimeOffset CreatedAt,
    int Reviews)
{
    public bool IsDue(DateTimeOffset now) => DueAt <= now;
}

/// <summary>Reads "中文 → English" lines from a translation hint; other lines are skipped.</summary>
public static partial class HintTerms
{
    private static readonly string[] Arrows = ["→", "->", "=>"];

    [System.Text.RegularExpressions.GeneratedRegex(@"^\d+[.、)）]\s*")]
    private static partial System.Text.RegularExpressions.Regex ListNumber();

    [System.Text.RegularExpressions.GeneratedRegex(@"\s*[（(][^()（）]*[)）]\s*$")]
    private static partial System.Text.RegularExpressions.Regex TrailingNote();

    public static IReadOnlyList<(string Front, string Back)> Read(string hint)
    {
        var pairs = new List<(string, string)>();
        foreach (var raw in hint.Split('\n'))
        {
            // Markdown emphasis and list numbers are formatting, not part of the term.
            var line = ListNumber().Replace(raw.Replace("*", "").Replace("__", "").Trim().TrimStart('-', '•', '·').Trim(), "");
            var arrow = Arrows.Select(a => (Arrow: a, At: line.IndexOf(a, StringComparison.Ordinal))).Where(x => x.At > 0).OrderBy(x => x.At).FirstOrDefault();
            if (arrow.Arrow is null) continue;
            var front = line[..arrow.At].Trim();
            var back = line[(arrow.At + arrow.Arrow.Length)..].Trim();
            // Only a usage note in brackets at the very end stays out of the card's back; brackets inside the
            // phrase ("take (sth.) into account") are part of it.
            back = TrailingNote().Replace(back, "").Trim();
            if (front.Length > 0 && back.Length > 0) pairs.Add((front, back));
        }
        return pairs;
    }
}

/// <summary>
/// Leitner boxes: six boxes whose gaps double from one day. Remembering moves a card up a box, a fuzzy answer keeps
/// it in place and brings it back after half the gap, forgetting sends it to the first box for another try soon.
/// </summary>
public static class WordSchedule
{
    public const int FirstBox = 1;
    public const int LastBox = 6;
    public static readonly TimeSpan RetryAfterForgetting = TimeSpan.FromMinutes(10);

    public static TimeSpan Gap(int box) => TimeSpan.FromDays(1 << (Math.Clamp(box, FirstBox, LastBox) - 1));

    /// <summary>
    /// Gaps of a day or more end at the start of that local day, so a card reviewed in the evening is due the
    /// next morning of its day rather than that evening; shorter waits stay exact.
    /// </summary>
    public static (int Box, DateTimeOffset Due) Next(int box, Recall recall, DateTimeOffset now, TimeZoneInfo zone) => recall switch
    {
        Recall.Remembered => (Math.Min(box + 1, LastBox), After(now, Gap(Math.Min(box + 1, LastBox)), zone)),
        Recall.Fuzzy => (box, After(now, Gap(box) / 2, zone)),
        _ => (FirstBox, now + RetryAfterForgetting),
    };

    internal static DateTimeOffset StartOfDay(DateOnly day, TimeZoneInfo zone)
    {
        var local = day.ToDateTime(TimeOnly.MinValue);
        if (zone.IsInvalidTime(local))
        {
            for (var m = 1; m < 24 * 60; m++)
            {
                var candidate = local.AddMinutes(m);
                if (!zone.IsInvalidTime(candidate))
                {
                    local = candidate;
                    break;
                }
            }
        }
        var offset = zone.IsAmbiguousTime(local)
            ? zone.GetAmbiguousTimeOffsets(local).Max()
            : zone.GetUtcOffset(local);
        return new DateTimeOffset(local, offset);
    }

    private static DateTimeOffset After(DateTimeOffset now, TimeSpan gap, TimeZoneInfo zone)
    {
        if (gap < TimeSpan.FromDays(1)) return now + gap;
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now, zone).Date);
        return StartOfDay(today.AddDays(gap.Days), zone);
    }
}
