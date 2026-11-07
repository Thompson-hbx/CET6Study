using System.Text;
using System.Text.Json.Nodes;

namespace Zhupi.Core.Tests;

public sealed class ExamPackTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-papers-");
    private readonly Notebook _book;

    public ExamPackTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), TimeProvider.System);

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    // Made-up content in the pack's shape; no real exam text.
    private static JsonObject Paper(string date = "2030-06-15", int set = 1, int revision = 1) => new()
    {
        ["stableId"] = $"cet6-{date}-{set}",
        ["examDate"] = date,
        ["setCode"] = set.ToString(System.Globalization.CultureInfo.InvariantCulture),
        ["revision"] = revision,
        ["sourceName"] = "测试样卷",
        ["sourceUrl"] = "",
        ["licenseStatus"] = "original",
        ["reviewed"] = true,
        ["sample"] = false,
        ["passages"] = new JsonArray(),
        ["writingPrompt"] = "Write an essay on keeping a paper notebook.",
        ["writingInstructions"] = "You should write at least 150 words but no more than 200 words.",
        ["chineseText"] = "纸质笔记本仍有人用。它让人慢下来。",
        ["referenceTranslation"] = "",
        ["referenceSource"] = "",
    };

    private static byte[] Pack(params JsonObject[] papers) => Encoding.UTF8.GetBytes(new JsonObject
    {
        ["format"] = "CET6ExamPack",
        ["version"] = 1,
        ["papers"] = new JsonArray(papers),
    }.ToJsonString());

    [Fact]
    public void ReadsPapersAndBuildsPageText()
    {
        var pack = ExamPackFile.Parse(Pack(Paper()));

        var paper = Assert.Single(pack.Papers);
        Assert.Empty(pack.Skipped);
        Assert.Equal("2030 年 6 月 · 第 1 套", paper.Label);
        Assert.Equal("2030 年 6 月 · 第 1 套 · 翻译", paper.PageTitle(PieceKind.Translation));
        Assert.Equal("Write an essay on keeping a paper notebook.\n\nYou should write at least 150 words but no more than 200 words.",
            paper.Task(PieceKind.Essay));
        Assert.Equal("纸质笔记本仍有人用。它让人慢下来。", paper.Task(PieceKind.Translation));
    }

    [Fact]
    public void InstructionsAlreadyInThePromptAreNotRepeated()
    {
        var p = Paper();
        p["writingPrompt"] = "Directions: write about notebooks. You should write at least 150 words.";
        p["writingInstructions"] = "You should write at least 150 words.";

        Assert.Equal("Directions: write about notebooks. You should write at least 150 words.",
            ExamPackFile.Parse(Pack(p)).Papers.Single().EssayTask);
    }

    [Fact]
    public void BadPapersAreSkippedWithAReason()
    {
        var sample = Paper("2030-06-15", 2);
        sample["sample"] = true;
        var mismatched = Paper("2030-06-15", 3);
        mismatched["setCode"] = "4";
        var unlicensed = Paper("2030-06-15", 5);
        unlicensed["licenseStatus"] = "";
        var empty = Paper("2030-06-15", 6);
        empty["writingPrompt"] = "";
        empty["chineseText"] = " ";

        var pack = ExamPackFile.Parse(Pack(Paper(), sample, mismatched, unlicensed, empty, Paper()));

        Assert.Equal(["cet6-2030-06-15-1"], pack.Papers.Select(p => p.Id));
        Assert.Equal(
            [
                ("cet6-2030-06-15-2", "是格式样本，不是真题"),
                ("cet6-2030-06-15-3", "stableId 和考试日期、套次对不上"),
                ("cet6-2030-06-15-5", "没有写明使用权（licenseStatus）"),
                ("cet6-2030-06-15-6", "没有作文题，也没有翻译原文"),
                ("cet6-2030-06-15-1", "同一个包里重复出现，只用第一次的"),
            ],
            pack.Skipped.Select(s => (s.Id, s.Reason)));
    }

    [Theory]
    [InlineData("not json", "这个文件不是有效的 JSON，没有导入。")]
    [InlineData("""{"format":"Other","version":1,"papers":[]}""", "这不是朱批认识的内容包（format 应为 CET6ExamPack）。")]
    [InlineData("""{"format":"CET6ExamPack","version":2,"papers":[]}""", "内容包版本不是 1，这个版本的朱批读不了。")]
    [InlineData("""{"format":"CET6ExamPack","version":1}""", "内容包里没有 papers 列表。")]
    public void RejectsFilesThatAreNotAPack(string json, string message)
    {
        Assert.Equal(message, Assert.Throws<NotebookException>(() => ExamPackFile.Parse(Encoding.UTF8.GetBytes(json))).Message);
    }

    [Fact]
    public void RejectsTooManyPapers()
    {
        var papers = Enumerable.Range(1, ExamPackFile.MaxPapers + 1).Select(i => Paper(set: i)).ToArray();

        Assert.Throws<NotebookException>(() => ExamPackFile.Parse(Pack(papers)));
    }

    [Fact]
    public void ReimportingFollowsTheRevision()
    {
        Assert.Equal((1, 0, 0), Counts(_book.ImportPapers(ExamPackFile.Parse(Pack(Paper())))));
        Assert.Equal((0, 0, 1), Counts(_book.ImportPapers(ExamPackFile.Parse(Pack(Paper())))));

        var changed = Paper();
        changed["chineseText"] = "改过的原文。";
        var conflict = _book.ImportPapers(ExamPackFile.Parse(Pack(changed)));
        Assert.Equal("修订号相同但内容不同，没有覆盖；改过内容请把 revision 加一", Assert.Single(conflict.Skipped).Reason);
        Assert.Equal("纸质笔记本仍有人用。它让人慢下来。", _book.Paper("cet6-2030-06-15-1").ChineseText);

        var newer = Paper(revision: 2);
        newer["chineseText"] = "改过的原文。";
        Assert.Equal((0, 1, 0), Counts(_book.ImportPapers(ExamPackFile.Parse(Pack(newer)))));
        Assert.Equal("改过的原文。", _book.Paper("cet6-2030-06-15-1").ChineseText);

        var older = _book.ImportPapers(ExamPackFile.Parse(Pack(Paper())));
        Assert.Equal("本子里已经是更新的修订（2），没有退回旧的", Assert.Single(older.Skipped).Reason);
    }

    [Fact]
    public void StartingAPartReusesTheOpenDraftAndRewritesKeepThePaper()
    {
        _book.ImportPapers(ExamPackFile.Parse(Pack(Paper())));

        var page = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation);
        Assert.Equal(("2030 年 6 月 · 第 1 套 · 翻译", "纸质笔记本仍有人用。它让人慢下来。"), (page.Title, page.Task));
        Assert.Equal(page.Id, _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation).Id);

        _book.Write(page.Id, page.Title, page.Task, "Some people still use paper notebooks.");
        _book.HandIn(page.Id);
        var rewrite = _book.Rewrite(page.Id);
        Assert.Equal("cet6-2030-06-15-1", _book.PaperOf(rewrite.Id)?.Id);
        Assert.Null(_book.PaperOf(_book.NewPage(PieceKind.Translation).Id));

        var shelf = Assert.Single(_book.Shelf());
        Assert.Equal(new PartProgress(2, 1, rewrite.Id, rewrite.Number, true), shelf.Translation);
        Assert.Equal(new PartProgress(0, 0, null, null, false), shelf.Essay);
    }

    [Fact]
    public void AfterHandingInAnotherAttemptIsANewPage()
    {
        _book.ImportPapers(ExamPackFile.Parse(Pack(Paper())));
        var first = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Essay);
        _book.Write(first.Id, first.Title, first.Task, "Paper notebooks slow us down.");
        _book.HandIn(first.Id);

        var second = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Essay);

        Assert.NotEqual(first.Id, second.Id);
        Assert.Null(second.Revises);
        Assert.Equal(2, _book.Shelf().Single().Essay.Pages);
    }

    private static (int, int, int) Counts(PackImport import) => (import.Added, import.Updated, import.Unchanged);
}
