using System.Globalization;
using System.IO;
using Inkwell.Core;

namespace Inkwell.App.ViewModels;

public sealed partial class PageViewModel
{
    public string MarkdownFileName => string.Create(CultureInfo.InvariantCulture,
        $"{(IsEssay ? "作文" : "翻译")}-{_page.Number:00}.md");

    public void ExportMarkdown(string path)
    {
        if (!string.Equals(Path.GetExtension(path), ".md", StringComparison.OrdinalIgnoreCase))
        {
            _say("请把导出文件保存为 .md。");
            return;
        }

        try
        {
            var snapshot = _page with { Title = Title, Task = Task, Text = Text };
            var review = Review is { } current
                ? current with { Marks = Marks.Select(m => m.Placed with { Decision = m.Decision }).ToArray() }
                : null;
            var document = PageMarkdown.Format(snapshot, review);
            var destination = Path.GetFullPath(path);
            var temp = Path.Combine(Path.GetDirectoryName(destination)!, $".inkwell-export-{Guid.NewGuid():N}.tmp");
            try
            {
                File.WriteAllText(temp, document);
                File.Move(temp, destination, overwrite: true);
            }
            finally
            {
                File.Delete(temp);
            }
            _say($"已导出「{Path.GetFileName(destination)}」");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            _say($"导出失败：{e.Message}");
        }
    }
}
