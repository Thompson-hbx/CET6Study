using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Inkwell.Core.Teacher;

namespace Inkwell.Core;

public static partial class PageMarkdown
{
    public static string Format(Piece page, StoredReview? review)
    {
        var essay = page.Kind == PieceKind.Essay;
        var kind = essay ? "作文" : "翻译";
        var title = string.IsNullOrWhiteSpace(page.Title) ? $"未命名{kind}" : page.Title;
        var status = page.IsHandedIn ? "已交" : page.Revises is null ? "草稿" : "重写稿";
        var document = new StringBuilder();
        document.Append("# ").AppendLine(Inline(title)).AppendLine();
        document.AppendLine(CultureInfo.InvariantCulture, $"{kind} · 第 {page.Number:00} 页 · {status}").AppendLine();

        document.AppendLine(essay ? "## 题目" : "## 中文原文").AppendLine();
        AppendText(document, page.Task);
        document.AppendLine(essay ? "## 正文" : "## 译文").AppendLine();
        AppendText(document, page.Text);
        if (review is not null) AppendReview(document, review);
        return document.ToString();
    }

    private static void AppendReview(StringBuilder document, StoredReview review)
    {
        document.AppendLine("## 批改").AppendLine();
        document.AppendLine(CultureInfo.InvariantCulture, $"模拟档位：{review.Band}").AppendLine();
        if (!string.IsNullOrWhiteSpace(review.Verdict)) AppendText(document, $"总评：{review.Verdict.Trim()}");
        if (review.Dimensions.Count > 0)
        {
            foreach (var dimension in review.Dimensions)
                document.Append("- ").AppendLine(Inline($"{dimension.Name}：{dimension.Level}"));
            document.AppendLine();
        }

        foreach (var placed in review.Marks)
        {
            var mark = placed.Mark;
            var severity = mark.Severity == Severity.Must ? "必改" : "可改";
            var decision = placed.Decision switch
            {
                Decision.Taken => "已采纳",
                Decision.Left => "不改",
                _ => "待处理",
            };
            document.Append(CultureInfo.InvariantCulture, $"### 批注 {mark.Number} · {severity}");
            if (!string.IsNullOrWhiteSpace(mark.Kind)) document.Append(" · ").Append(Inline(mark.Kind));
            document.Append(" · ").Append(decision);
            if (!placed.IsPlaced) document.Append(" · 未定位");
            document.AppendLine().AppendLine();
            AppendText(document, $"原句：{mark.Quote.Trim()}\n改法：{mark.Fix.Trim()}\n说明：{mark.Why.Trim()}");
        }
    }

    private static void AppendText(StringBuilder document, string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            document.AppendLine("（空白）").AppendLine();
            return;
        }

        var lines = value.ReplaceLineEndings("\n").Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            var line = lines[i].Trim(' ', '\t');
            document.Append(Escape(line));
            if (line.Length > 0 && i + 1 < lines.Length && !string.IsNullOrWhiteSpace(lines[i + 1])) document.Append("  ");
            document.AppendLine();
        }
        document.AppendLine();
    }

    private static string Inline(string value) => Escape(value.ReplaceLineEndings(" ").Trim());

    private static string Escape(string value) => MarkdownSyntax().Replace(value, @"\$&");

    // The learner's text and the teacher's feedback are literal content, including on a line by themselves.
    [GeneratedRegex("""[\\*_{}\[\]<>#|~&$`]|^[+=-]|(?<=^[0-9]{1,9})[.)](?=\s|$)""")]
    private static partial Regex MarkdownSyntax();
}
