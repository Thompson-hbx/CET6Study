using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;

namespace Inkwell.Core.Tests;

public sealed class ExamPackTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-papers-");
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
    public void ALocallyFormattedSetNumberDoesNotRejectAnotherPaper()
    {
        var invalid = Paper(set: 2);
        invalid["stableId"] = "cet6-2030-06-15-٢";

        var pack = ExamPackFile.Parse(Pack(invalid, Paper()));

        Assert.Single(pack.Skipped);
        Assert.Equal(1, Assert.Single(pack.Papers).Set);
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
    [InlineData("""{"format":"Other","version":1,"papers":[]}""", "这不是墨水瓶认识的内容包（format 应为 CET6ExamPack）。")]
    [InlineData("""{"format":"CET6ExamPack","version":2,"papers":[]}""", "内容包版本不是 1，这个版本的墨水瓶读不了。")]
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
    public void AnUntouchedDraftTakesOverACorrectedTaskAndAWrittenOneKeepsItsOwn()
    {
        _book.ImportPapers(ExamPackFile.Parse(Pack(Paper())));
        var empty = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation);
        var essay = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Essay);
        _book.Write(essay.Id, essay.Title, essay.Task, "I have started.");

        var newer = Paper(revision: 2);
        newer["chineseText"] = "改过的原文。";
        newer["writingPrompt"] = "A corrected essay prompt.";
        _book.ImportPapers(ExamPackFile.Parse(Pack(newer)));

        Assert.Equal("改过的原文。", _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation).Task);
        Assert.Equal(empty.Id, _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation).Id);
        Assert.Equal(essay.Task, _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Essay).Task);
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

    [Fact]
    public void AFolderOfPacksImportsOnceAndReportsBadFiles()
    {
        var folder = Directory.CreateDirectory(Path.Combine(_dir.FullName, "packs")).FullName;
        File.WriteAllBytes(Path.Combine(folder, "a.json"), Pack(Paper()));
        File.WriteAllBytes(Path.Combine(folder, "b.json"), Pack(Paper(set: 2)));
        File.WriteAllText(Path.Combine(folder, "broken.json"), "not json");
        File.WriteAllText(Path.Combine(folder, "notes.txt"), "ignored");

        var first = ExamPackFolder.ImportAll(_book, folder);
        var again = ExamPackFolder.ImportAll(_book, folder);

        Assert.Equal((2, 0, 0), (first.Added, first.Updated, first.Unchanged));
        Assert.Equal(["broken.json"], first.Unreadable);
        Assert.Equal((0, 0, 2), (again.Added, again.Updated, again.Unchanged));
        Assert.Equal(2, _book.Shelf().Count);
        Assert.False(ExamPackFolder.ImportAll(_book, Path.Combine(_dir.FullName, "missing")).ChangedAnything);
    }

    [Fact]
    public void ReadsUtf8BomPackAndRejectsBomOnly()
    {
        var payload = Pack(Paper());
        var withBom = new byte[] { 0xEF, 0xBB, 0xBF }.Concat(payload).ToArray();

        var pack = ExamPackFile.Parse(withBom);

        Assert.Equal("cet6-2030-06-15-1", Assert.Single(pack.Papers).Id);
        Assert.Throws<NotebookException>(() => ExamPackFile.Parse([0xEF, 0xBB, 0xBF]));
    }

    [Fact]
    public void BoundedStreamReadWorksWithoutLengthAndRejectsOverflow()
    {
        var valid = Pack(Paper());
        using var okStream = new NonSeekableStream(valid);
        Assert.Equal(valid, ExamPackFile.ReadBounded(okStream));

        var tooLarge = new byte[ExamPackFile.MaxBytes + 1];
        using var bigStream = new NonSeekableStream(tooLarge);
        Assert.Throws<NotebookException>(() => ExamPackFile.ReadBounded(bigStream));
    }

    [Fact]
    public void RejectsLeadingZeroStableIdDuplicatesBothInPackAndAcrossImports()
    {
        var zeroId = Paper("2030-06-15", 1);
        zeroId["stableId"] = "cet6-2030-06-15-01";
        var normal = Paper("2030-06-15", 1);

        var firstOrder = ExamPackFile.Parse(Pack(normal, zeroId));
        Assert.Equal("cet6-2030-06-15-1", Assert.Single(firstOrder.Papers).Id);
        Assert.Equal("cet6-2030-06-15-01", Assert.Single(firstOrder.Skipped).Id);

        var zeroId2 = Paper("2030-06-15", 1);
        zeroId2["stableId"] = "cet6-2030-06-15-01";
        var normal2 = Paper("2030-06-15", 1);
        var reverseOrder = ExamPackFile.Parse(Pack(zeroId2, normal2));
        Assert.Equal("cet6-2030-06-15-1", Assert.Single(reverseOrder.Papers).Id);
        Assert.Equal("cet6-2030-06-15-01", Assert.Single(reverseOrder.Skipped).Id);

        // Even if an ExamPack object is constructed directly with two different IDs for the same date+set,
        // Notebook.ImportPapers refuses creating duplicate (exam_date, set_code) rows.
        var synthetic = new ExamPack(
            [
                firstOrder.Papers.Single(),
                firstOrder.Papers.Single() with { Id = "cet6-2030-06-15-01" },
            ],
            []);
        var import = _book.ImportPapers(synthetic);
        Assert.Equal(1, import.Added);
        Assert.Single(import.Skipped);
        Assert.Single(_book.Shelf());
    }

    [Fact]
    public void LocalSourceUrlIsAccepted()
    {
        var p = Paper();
        p["sourceUrl"] = "local:tools/exam-sample.json";

        var pack = ExamPackFile.Parse(Pack(p));

        Assert.Equal("local:tools/exam-sample.json", Assert.Single(pack.Papers).SourceUrl);
    }

    [Fact]
    public void BlankWritingPromptWithInstructionsMeansNoEssay()
    {
        var p = Paper();
        p["writingPrompt"] = "  ";
        p["writingInstructions"] = "You should write at least 150 words.";
        var paper = Assert.Single(ExamPackFile.Parse(Pack(p)).Papers);

        Assert.False(paper.HasEssay);
        Assert.Equal("", paper.EssayTask);
        Assert.True(paper.HasTranslation);

        _book.ImportPapers(new ExamPack([paper], []));
        var ex = Assert.Throws<NotebookException>(() => _book.StartFromPaper(paper.Id, PieceKind.Essay));
        Assert.Equal("这套卷子没有作文题。", ex.Message);
    }

    [Fact]
    public void UnreadableFolderWithTrailingSeparatorReportsFolderName()
    {
        var target = Directory.CreateDirectory(Path.Combine(_dir.FullName, "gone"));
        var broken = Path.Combine(_dir.FullName, "broken-packs");
        using var mklink = Process.Start(new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{broken}\" \"{target.FullName}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
        });
        mklink!.WaitForExit();
        Assert.Equal(0, mklink.ExitCode);
        target.Delete();

        try
        {
            var result = ExamPackFolder.ImportAll(_book, broken + Path.DirectorySeparatorChar);
            var doubleBackslash = ExamPackFolder.ImportAll(_book, broken + "\\\\");
            var doubleSlash = ExamPackFolder.ImportAll(_book, broken + "//");

            Assert.Equal(["broken-packs"], result.Unreadable);
            Assert.Equal(["broken-packs"], doubleBackslash.Unreadable);
            Assert.Equal(["broken-packs"], doubleSlash.Unreadable);
        }
        finally
        {
            if (Directory.Exists(broken)) Directory.Delete(broken, recursive: false);
        }
    }

    [Fact]
    public void ShelfReportsMultiPaperProgressExcludingDiscardedPages()
    {
        _book.ImportPapers(ExamPackFile.Parse(Pack(Paper("2030-06-15", 1), Paper("2030-12-14", 2))));
        var e1 = _book.StartFromPaper("cet6-2030-12-14-2", PieceKind.Essay);
        _book.Write(e1.Id, e1.Title, e1.Task, "First attempt");
        _book.HandIn(e1.Id);
        var e2 = _book.StartFromPaper("cet6-2030-12-14-2", PieceKind.Essay);
        var discarded = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation);
        _book.Discard(discarded.Id);

        var shelf = _book.Shelf();

        Assert.Equal(["cet6-2030-12-14-2", "cet6-2030-06-15-1"], shelf.Select(s => s.Paper.Id));
        Assert.Equal(new PartProgress(2, 1, e2.Id, e2.Number, true), shelf[0].Essay);
        Assert.Equal(new PartProgress(0, 0, null, null, false), shelf[1].Translation);
    }

    private static (int, int, int) Counts(PackImport import) => (import.Added, import.Updated, import.Unchanged);

    private sealed class NonSeekableStream(byte[] data) : Stream
    {
        private int _offset;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count)
        {
            var available = Math.Min(count, data.Length - _offset);
            if (available <= 0) return 0;
            Array.Copy(data, _offset, buffer, offset, available);
            _offset += available;
            return available;
        }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
