using System.Text.RegularExpressions;

namespace Zhupi.Core;

public enum CheckState
{
    Done,
    Todo,
    Info,
}

public sealed record CheckLine(string Text, CheckState State);

/// <summary>A word target read from the task, or the usual CET-6 range when the task does not say.</summary>
public sealed record WordTarget(int Min, int Max, bool FromTask);

/// <summary>
/// Pencil checks shown beside the page before handing in. They only read the task and the text;
/// nothing here calls a model or judges quality.
/// </summary>
public static partial class SelfCheck
{
    public static readonly WordTarget UsualEssayTarget = new(150, 200, FromTask: false);

    public static int Words(string text) => EnglishWord().Count(text);

    public static WordTarget EssayTarget(string task)
    {
        var range = WordRange().Match(task);
        if (range.Success &&
            int.TryParse(range.Groups["min"].Value, out var min) && int.TryParse(range.Groups["max"].Value, out var max) &&
            min > 0 && max >= min)
        {
            return new WordTarget(min, max, FromTask: true);
        }
        return UsualEssayTarget;
    }

    /// <summary>The sentence an essay must start with, when the task quotes one.</summary>
    public static string? RequiredOpening(string task)
    {
        var match = Opening().Match(task);
        return match.Success ? match.Groups["s"].Value.Trim() : null;
    }

    public static IReadOnlyList<CheckLine> Essay(string task, string text)
    {
        var lines = new List<CheckLine>();
        var target = EssayTarget(task);
        var words = Words(text);
        var range = target.FromTask ? $"{target.Min}–{target.Max}" : $"{target.Min}–{target.Max}（六级常见要求）";
        lines.Add(words < target.Min
            ? new CheckLine($"字数 {words}，还差 {target.Min - words} 个词（{range}）", CheckState.Todo)
            : words > target.Max
                ? new CheckLine($"字数 {words}，多了 {words - target.Max} 个词（{range}）", CheckState.Todo)
                : new CheckLine($"字数 {words}，在 {range} 之内", CheckState.Done));

        if (RequiredOpening(task) is { } opening)
        {
            lines.Add(Simplified(text).StartsWith(Simplified(opening), StringComparison.OrdinalIgnoreCase)
                ? new CheckLine("用了给定的开头句", CheckState.Done)
                : new CheckLine("还没用给定的开头句开头", CheckState.Todo));
        }

        var paragraphs = Paragraphs(text);
        lines.Add(paragraphs >= 3
            ? new CheckLine($"写了 {paragraphs} 段", CheckState.Done)
            : new CheckLine($"现在 {paragraphs} 段，一般写三段：观点、展开、结论", CheckState.Todo));
        return lines;
    }

    public static IReadOnlyList<CheckLine> Translation(string task, string text)
    {
        var source = SourceText.Sentences(task).Count;
        var translated = EnglishSentences(text);
        var lines = new List<CheckLine>();
        if (source == 0)
        {
            lines.Add(new CheckLine("先把中文原文放进来", CheckState.Todo));
            return lines;
        }
        lines.Add(translated >= source
            ? new CheckLine($"原文 {source} 句，译文写了 {translated} 句", CheckState.Done)
            : new CheckLine($"原文 {source} 句，译文才 {translated} 句，看看有没有漏译", CheckState.Todo));
        lines.Add(new CheckLine("数字、年份和专有名词逐个对一遍", CheckState.Info));
        return lines;
    }

    /// <summary>English sentences that contain at least one word, split at . ! and ?.</summary>
    public static int EnglishSentences(string text) => EnglishSentence().Matches(text).Count(m => Words(m.Value) > 0);

    public static int Paragraphs(string text) =>
        text.Split('\n').Count(line => Words(line) > 0);

    // Straight and curly quotes, case and runs of spaces should not decide whether the opening matches.
    private static string Simplified(string value) =>
        Spaces().Replace(value.Replace('’', '\'').Replace('‘', '\'').Replace("“", "").Replace("”", "").Replace("\"", ""), " ").Trim();

    [GeneratedRegex(@"[A-Za-z]+(?:['’-][A-Za-z]+)*")]
    private static partial Regex EnglishWord();

    [GeneratedRegex(@"(?:at least\s+(?<min>\d{2,3})\s+words\s+but\s+no\s+more\s+than\s+(?<max>\d{2,3})\s+words)|(?:(?<min>\d{2,3})\s*(?:-|–|~|to|至|到)\s*(?<max>\d{2,3})\s*(?:words|个?词))",
        RegexOptions.IgnoreCase)]
    private static partial Regex WordRange();

    [GeneratedRegex(@"begins?\s+with\s+the\s+(?:following\s+)?sentence[:：]?\s*[“""](?<s>[^”""]{4,300})[”""]", RegexOptions.IgnoreCase)]
    private static partial Regex Opening();

    [GeneratedRegex(@"[^.!?]+[.!?]?")]
    private static partial Regex EnglishSentence();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();
}
