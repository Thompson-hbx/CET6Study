using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Zhupi.Core;

/// <summary>
/// One past paper's essay and translation parts, as declared by the person who prepared the content pack.
/// Reading parts of a pack are not used.
/// </summary>
public sealed record ExamPaper(
    string Id,
    DateOnly ExamDate,
    int Set,
    int Revision,
    string SourceName,
    string SourceUrl,
    string License,
    bool Reviewed,
    string WritingPrompt,
    string WritingInstructions,
    string ChineseText,
    string ReferenceTranslation,
    string ReferenceTranslationSource,
    string ReferenceEssay,
    string ReferenceEssaySource)
{
    public string Label => $"{ExamDate.Year} 年 {ExamDate.Month} 月 · 第 {Set} 套";

    public bool HasEssay => WritingPrompt.Trim().Length > 0;

    public bool HasTranslation => ChineseText.Trim().Length > 0;

    /// <summary>
    /// What goes on the essay page's task slip. Some packs keep the directions in the prompt and use the
    /// instructions field for a note, others split them, so both are kept, prompt first, without repeats.
    /// </summary>
    public string EssayTask
    {
        get
        {
            var prompt = WritingPrompt.Trim();
            var instructions = WritingInstructions.Trim();
            return instructions.Length == 0 || prompt.Contains(instructions, StringComparison.Ordinal)
                ? prompt
                : prompt + "\n\n" + instructions;
        }
    }

    public string Task(PieceKind kind) => kind == PieceKind.Essay ? EssayTask : ChineseText.Trim();

    public string PageTitle(PieceKind kind) => $"{Label} · {(kind == PieceKind.Essay ? "作文" : "翻译")}";
}

/// <summary>A paper left out of an import, and why.</summary>
public sealed record PackSkip(string Id, string Reason);

public sealed record ExamPack(IReadOnlyList<ExamPaper> Papers, IReadOnlyList<PackSkip> Skipped);

/// <summary>
/// Reads a CET6ExamPack file (format version 1). Problems with the whole file stop the import; problems with
/// one paper only skip that paper, so one bad entry does not block the rest.
/// </summary>
public static partial class ExamPackFile
{
    public const long MaxBytes = 4 * 1024 * 1024;
    public const int MaxPapers = 40;

    private static readonly string[] Licenses = ["personal-use", "redistributable", "original"];

    public static ExamPack Read(string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists) throw new NotebookException("找不到这个内容包文件。");
        if (info.Length > MaxBytes) throw new NotebookException("内容包超过 4 MB，不像是作文和翻译的内容包，没有导入。");
        return Parse(File.ReadAllBytes(path));
    }

    public static ExamPack Parse(byte[] json)
    {
        if (json.LongLength > MaxBytes) throw new NotebookException("内容包超过 4 MB，不像是作文和翻译的内容包，没有导入。");
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 16 });
        }
        catch (JsonException)
        {
            throw new NotebookException("这个文件不是有效的 JSON，没有导入。");
        }
        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object || Text(root, "format") != "CET6ExamPack")
                throw new NotebookException("这不是朱批认识的内容包（format 应为 CET6ExamPack）。");
            if (!root.TryGetProperty("version", out var version) || version.ValueKind != JsonValueKind.Number
                || !version.TryGetInt32(out var v) || v != 1)
                throw new NotebookException("内容包版本不是 1，这个版本的朱批读不了。");
            if (!root.TryGetProperty("papers", out var papers) || papers.ValueKind != JsonValueKind.Array)
                throw new NotebookException("内容包里没有 papers 列表。");
            if (papers.GetArrayLength() > MaxPapers) throw new NotebookException($"内容包超过 {MaxPapers} 套，请拆开导入。");

            var read = new List<ExamPaper>();
            var skipped = new List<PackSkip>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var index = 0;
            foreach (var element in papers.EnumerateArray())
            {
                index++;
                var id = element.ValueKind == JsonValueKind.Object ? Text(element, "stableId") : "";
                var name = id.Length > 0 ? id : $"第 {index} 项";
                var reason = Check(element, out var paper);
                if (reason is null && !seen.Add(paper!.Id)) reason = "同一个包里重复出现，只用第一次的";
                if (reason is null) read.Add(paper!);
                else skipped.Add(new PackSkip(name, reason));
            }
            return new ExamPack(read, skipped);
        }
    }

    private static string? Check(JsonElement element, out ExamPaper? paper)
    {
        paper = null;
        if (element.ValueKind != JsonValueKind.Object) return "不是一套试卷的格式";
        if (element.TryGetProperty("sample", out var sample) && sample.ValueKind == JsonValueKind.True) return "是格式样本，不是真题";

        var id = Text(element, "stableId");
        var match = IdPattern().Match(id);
        if (!match.Success) return "stableId 应写成 cet6-年-月-日-套次";
        if (!DateOnly.TryParseExact(Text(element, "examDate"), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date))
            return "examDate 不是 yyyy-MM-dd 日期";
        if (!int.TryParse(Text(element, "setCode"), NumberStyles.None, CultureInfo.InvariantCulture, out var set) || set < 1)
            return "setCode 不是套次数字";
        if (match.Groups["date"].Value != date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
            || int.Parse(match.Groups["set"].Value, CultureInfo.InvariantCulture) != set)
            return "stableId 和考试日期、套次对不上";
        if (!element.TryGetProperty("revision", out var revisionElement) || revisionElement.ValueKind != JsonValueKind.Number
            || !revisionElement.TryGetInt32(out var revision) || revision < 1)
            return "revision 应为正整数";
        var license = Text(element, "licenseStatus");
        if (!Licenses.Contains(license)) return "没有写明使用权（licenseStatus）";
        if (Text(element, "sourceName").Length == 0) return "没有写明来源（sourceName）";

        paper = new ExamPaper(
            Id: id,
            ExamDate: date,
            Set: set,
            Revision: revision,
            SourceName: Text(element, "sourceName"),
            SourceUrl: Text(element, "sourceUrl"),
            License: license,
            Reviewed: element.TryGetProperty("reviewed", out var reviewed) && reviewed.ValueKind == JsonValueKind.True,
            WritingPrompt: Text(element, "writingPrompt"),
            WritingInstructions: Text(element, "writingInstructions"),
            ChineseText: Text(element, "chineseText"),
            ReferenceTranslation: Text(element, "referenceTranslation"),
            ReferenceTranslationSource: Text(element, "referenceSource"),
            ReferenceEssay: Text(element, "referenceWriting"),
            ReferenceEssaySource: Text(element, "referenceWritingSource"));
        if (!paper.HasEssay && !paper.HasTranslation)
        {
            paper = null;
            return "没有作文题，也没有翻译原文";
        }
        return null;
    }

    /// <summary>A string field, trimmed; anything missing or of another type counts as empty.</summary>
    private static string Text(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString()!.Trim() : "";

    [GeneratedRegex(@"^cet6-(?<date>\d{4}-\d{2}-\d{2})-(?<set>\d{1,2})$", RegexOptions.CultureInvariant)]
    private static partial Regex IdPattern();
}
