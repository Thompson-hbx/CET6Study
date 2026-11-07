using Zhupi.Core.Teacher;

namespace Zhupi.Core.Tests;

public sealed class MarkingTest : IDisposable
{
    private const string Essay =
        "Night markets bring cities to life. People enjoys walking among stalls.\n" +
        "However, the night economy also bring many problems to residents.";

    private const string Reply = """
        {
          "band": 11,
          "verdict": "观点清楚，动词形式要细心。",
          "dimensions": [{"name": "切题", "level": "好"}, {"name": "语言", "level": "待改进"}, {"name": "结构", "level": "很好"}],
          "marks": [
            {"quote": "People enjoys", "fix": "People enjoy", "why": "主语复数", "severity": "must", "kind": "语法"},
            {"quote": "not in the essay", "fix": "x", "why": "?", "severity": "maybe", "kind": "用词"},
            {"quote": "also bring", "fix": "also brings", "why": "主语单数", "severity": "must", "kind": "语法"},
            {"quote": "", "fix": "dropped", "why": "", "severity": "must", "kind": "语法"}
          ]
        }
        """;

    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-marking-");
    private readonly Notebook _book;

    public MarkingTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), new TickingClock());

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public void ReadsAValidReplyAndDropsWhatDoesNotFit()
    {
        var marking = MarkingProtocol.Read(Reply);

        Assert.Equal((11, "观点清楚，动词形式要细心。"), (marking.Band, marking.Verdict));
        Assert.Equal([new Dimension("切题", "好"), new Dimension("语言", "待改进")], marking.Dimensions);
        Assert.Equal([1, 2, 3], marking.Marks.Select(m => m.Number));
        Assert.Equal(new Mark(1, "People enjoys", "People enjoy", "主语复数", Severity.Must, "语法"), marking.Marks[0]);
        Assert.Equal(Severity.Maybe, marking.Marks[1].Severity);
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("[1,2]")]
    [InlineData("{\"band\": 12, \"marks\": []}")]
    [InlineData("{\"band\": \"11\"}")]
    public void RejectsRepliesThatBreakTheShape(string reply) =>
        Assert.Throws<TeacherException>(() => MarkingProtocol.Read(reply));

    [Fact]
    public void AcceptsAReplyWrappedInAFence() =>
        Assert.Equal(8, MarkingProtocol.Read("```json\n{\"band\": 8}\n```").Band);

    [Fact]
    public void KeepsAtMostTwelveMarks()
    {
        var many = "{\"band\":5,\"marks\":[" + string.Join(",", Enumerable.Range(0, 20).Select(i => $"{{\"quote\":\"q{i}\"}}")) + "]}";

        Assert.Equal(MarkingProtocol.MaxMarks, MarkingProtocol.Read(many).Marks.Count);
    }

    [Fact]
    public void InstructionAndMaterialFenceTheStudentText()
    {
        Assert.Contains("切题、内容、结构、语言", MarkingProtocol.Instruction(PieceKind.Essay));
        Assert.Contains("准确、完整、语法、表达", MarkingProtocol.Instruction(PieceKind.Translation));
        Assert.Equal("中文原文：\n<<<\n原文\n>>>\n\n学生译文：\n<<<\nText\n>>>", MarkingProtocol.Material(PieceKind.Translation, " 原文 ", "Text\n"));
    }

    [Fact]
    public void LocatesQuotesInReadingOrderWithoutOverlap()
    {
        const string text = "the cat saw the cat. The Cat ran.";
        var marks = new[] { M(1, "the cat"), M(2, "the cat"), M(3, "the cat"), M(4, "dog") };

        Assert.Equal([0, 12, 21, null], Placement.Locate(text, marks));
    }

    [Fact]
    public void AppliesOnlyTakenPlacedFixes()
    {
        const string text = "a b c d";
        var placed = new[]
        {
            new PlacedMark(M(1, "b", "B"), 2, Decision.Taken),
            new PlacedMark(M(2, "c", "C"), 4, Decision.Left),
            new PlacedMark(M(3, "d", "D"), 6, Decision.Taken),
            new PlacedMark(M(4, "zz", "Z"), null, Decision.Taken),
        };

        Assert.Equal("a B c D", Placement.Apply(text, placed));
    }

    [Fact]
    public void StoresReviewsDecisionsAndRewritesWithTakenFixes()
    {
        var page = _book.NewPage(PieceKind.Essay);
        _book.Write(page.Id, "夜市", "Night markets", Essay);
        Assert.Throws<NotebookException>(() => _book.SaveReview(page.Id, MarkingProtocol.Read(Reply), "t"));
        _book.HandIn(page.Id);

        var review = _book.SaveReview(page.Id, MarkingProtocol.Read(Reply), "DeepSeek · deepseek-chat");
        Assert.Equal([Essay.IndexOf("People enjoys"), null, Essay.IndexOf("also bring")], review.Marks.Select(m => m.Start));
        _book.Decide(review.Id, 1, Decision.Taken);
        _book.Decide(review.Id, 3, Decision.Left);

        var stored = _book.LatestReview(page.Id)!;
        Assert.Equal((11, "DeepSeek · deepseek-chat"), (stored.Band, stored.Teacher));
        Assert.Equal([Decision.Taken, Decision.Open, Decision.Left], stored.Marks.Select(m => m.Decision));
        Assert.Equal(review.Dimensions, stored.Dimensions);
        Assert.Equal(1, stored.TakenCount);

        var second = _book.RewriteWithTaken(page.Id);
        Assert.Contains("People enjoy walking", second.Text);
        Assert.Contains("also bring many", second.Text);
        Assert.Equal(Essay, _book.Get(page.Id).Text);
        Assert.Null(_book.LatestReview(second.Id));
    }

    [Fact]
    public void AVersionOneNotebookGainsTheReviewTables()
    {
        var path = Path.Combine(_dir.FullName, "old.db");
        using (var raw = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False"))
        {
            raw.Open();
            using var command = raw.CreateCommand();
            command.CommandText = """
                CREATE TABLE pieces (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('essay', 'translation')),
                    number INTEGER NOT NULL CHECK (number > 0), title TEXT NOT NULL, task TEXT NOT NULL, text TEXT NOT NULL,
                    revises TEXT REFERENCES pieces (id), created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    handed_in_at INTEGER, origin TEXT NOT NULL CHECK (origin IN ('local', 'imported')));
                CREATE UNIQUE INDEX pieces_kind_number ON pieces (kind, number);
                INSERT INTO pieces VALUES ('p', 'essay', 1, 't', 'task', 'People enjoys it.', NULL, 1, 1, 2, 'local');
                PRAGMA user_version = 1;
                """;
            command.ExecuteNonQuery();
        }

        using var book = Notebook.Open(path, new TickingClock());

        Assert.Equal("People enjoys it.", book.Get("p").Text);
        Assert.NotNull(book.SaveReview("p", MarkingProtocol.Read(Reply), "t"));
    }

    private static Mark M(int n, string quote, string fix = "") => new(n, quote, fix, "", Severity.Must, "语法");
}
