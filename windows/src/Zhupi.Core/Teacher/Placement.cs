namespace Zhupi.Core.Teacher;

public enum Decision
{
    Open,
    Taken,
    Left,
}

/// <summary>A mark tied to a span of the handed-in text; <see cref="Start"/> is null when the quote is not in the text.</summary>
public sealed record PlacedMark(Mark Mark, int? Start, Decision Decision)
{
    public int Length => Mark.Quote.Length;

    public bool IsPlaced => Start is not null;
}

/// <summary>Finds each quoted snippet in the text and applies the fixes the learner took.</summary>
public static class Placement
{
    /// <summary>
    /// Marks come in reading order, so each search starts after the previous mark; a quote that only
    /// appears earlier is still found as long as it does not overlap a mark already placed.
    /// Case is ignored only when the exact spelling is nowhere in the text.
    /// </summary>
    public static IReadOnlyList<int?> Locate(string text, IReadOnlyList<Mark> marks)
    {
        var taken = new List<(int Start, int End)>();
        var cursor = 0;
        var result = new List<int?>();
        foreach (var mark in marks)
        {
            var start = Find(text, mark.Quote, cursor, taken, StringComparison.Ordinal)
                ?? Find(text, mark.Quote, 0, taken, StringComparison.Ordinal)
                ?? Find(text, mark.Quote, cursor, taken, StringComparison.OrdinalIgnoreCase)
                ?? Find(text, mark.Quote, 0, taken, StringComparison.OrdinalIgnoreCase);
            if (start is { } s)
            {
                taken.Add((s, s + mark.Quote.Length));
                cursor = s + mark.Quote.Length;
            }
            result.Add(start);
        }
        return result;
    }

    /// <summary>The text with every taken, placed fix applied; overlapping fixes keep the earlier one.</summary>
    public static string Apply(string text, IEnumerable<PlacedMark> marks)
    {
        var chosen = marks.Where(m => m.Decision == Decision.Taken && m.IsPlaced).OrderBy(m => m.Start).ToList();
        var result = new System.Text.StringBuilder();
        var at = 0;
        foreach (var m in chosen)
        {
            if (m.Start!.Value < at) continue;
            result.Append(text, at, m.Start.Value - at).Append(m.Mark.Fix);
            at = m.Start.Value + m.Length;
        }
        return result.Append(text, at, text.Length - at).ToString();
    }

    private static int? Find(string text, string quote, int from, List<(int Start, int End)> taken, StringComparison comparison)
    {
        if (quote.Length == 0) return null;
        for (var at = text.IndexOf(quote, from, comparison); at >= 0; at = text.IndexOf(quote, at + 1, comparison))
        {
            var end = at + quote.Length;
            if (taken.All(t => end <= t.Start || at >= t.End)) return at;
            if (at + 1 >= text.Length) break;
        }
        return null;
    }
}
