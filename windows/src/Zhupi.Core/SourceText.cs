using System.Text;

namespace Zhupi.Core;

/// <summary>Splits a Chinese passage for translation into numbered sentences.</summary>
public static class SourceText
{
    private const string Enders = "。！？!?";
    private const string Closers = "”’」』）)》】";

    /// <summary>
    /// Sentences in order. A sentence ends at 。！？ and keeps any closing quotes or brackets right after it;
    /// text without a final ender still counts as the last sentence. Whitespace containing a line break (a wrap in
    /// a copied PDF) is removed, except that two Latin letters or digits keep one space; other spaces stay as one.
    /// </summary>
    public static IReadOnlyList<string> Sentences(string passage)
    {
        var flat = Flatten(passage);
        var sentences = new List<string>();
        var current = new StringBuilder();
        for (var i = 0; i < flat.Length; i++)
        {
            current.Append(flat[i]);
            if (!Enders.Contains(flat[i])) continue;
            while (i + 1 < flat.Length && Closers.Contains(flat[i + 1])) current.Append(flat[++i]);
            Add(sentences, current);
        }
        Add(sentences, current);
        return sentences;
    }

    private static void Add(List<string> sentences, StringBuilder current)
    {
        var sentence = current.ToString().Trim();
        if (sentence.Length > 0) sentences.Add(sentence);
        current.Clear();
    }

    private static string Flatten(string passage)
    {
        var text = new StringBuilder(passage.Length);
        var i = 0;
        while (i < passage.Length)
        {
            if (!char.IsWhiteSpace(passage[i]))
            {
                text.Append(passage[i++]);
                continue;
            }
            var end = i;
            var lineBreak = false;
            while (end < passage.Length && char.IsWhiteSpace(passage[end]))
            {
                lineBreak |= passage[end] is '\n' or '\r';
                end++;
            }
            var before = text.Length > 0 ? text[^1] : '\0';
            var after = end < passage.Length ? passage[end] : '\0';
            // A break inside a run of text is a PDF line wrap; ordinary spaces were typed on purpose.
            if (!lineBreak || (IsLatin(before) && IsLatin(after))) text.Append(' ');
            i = end;
        }
        return text.ToString();
    }

    private static bool IsLatin(char c) => c is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9';
}
