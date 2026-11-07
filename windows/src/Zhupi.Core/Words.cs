namespace Zhupi.Core;

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
public static class HintTerms
{
    private static readonly string[] Arrows = ["→", "->", "=>"];

    public static IReadOnlyList<(string Front, string Back)> Read(string hint)
    {
        var pairs = new List<(string, string)>();
        foreach (var raw in hint.Split('\n'))
        {
            var line = raw.Trim().TrimStart('-', '*', '•', '·').Trim();
            var arrow = Arrows.Select(a => (Arrow: a, At: line.IndexOf(a, StringComparison.Ordinal))).Where(x => x.At > 0).OrderBy(x => x.At).FirstOrDefault();
            if (arrow.Arrow is null) continue;
            var front = line[..arrow.At].Trim();
            var back = line[(arrow.At + arrow.Arrow.Length)..].Trim();
            // A trailing usage note in brackets stays out of the card's back.
            var bracket = back.IndexOfAny(['（', '(']);
            if (bracket > 0) back = back[..bracket].Trim();
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

    public static (int Box, DateTimeOffset Due) Next(int box, Recall recall, DateTimeOffset now) => recall switch
    {
        Recall.Remembered => (Math.Min(box + 1, LastBox), now + Gap(Math.Min(box + 1, LastBox))),
        Recall.Fuzzy => (box, now + Gap(box) / 2),
        _ => (FirstBox, now + RetryAfterForgetting),
    };
}
