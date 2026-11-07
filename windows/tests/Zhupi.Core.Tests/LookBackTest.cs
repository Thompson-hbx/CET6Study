using Zhupi.Core.Teacher;

namespace Zhupi.Core.Tests;

public sealed class LookBackTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-lookback-");
    private readonly Notebook _book;

    public LookBackTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), new TickingClock());

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    private Piece HandedIn(PieceKind kind, string title, string text)
    {
        var page = _book.NewPage(kind);
        _book.Write(page.Id, title, "task", text);
        return _book.HandIn(page.Id);
    }

    private static Marking Marks(int band, params (string Quote, string Fix, string Severity, string Kind)[] marks) =>
        MarkingProtocol.Read(System.Text.Json.JsonSerializer.Serialize(new
        {
            band,
            marks = marks.Select(m => new { quote = m.Quote, fix = m.Fix, why = "", severity = m.Severity, kind = m.Kind }),
        }));

    [Fact]
    public void AnEmptyNotebookLooksBackOnNothing()
    {
        var look = _book.LookBack();

        Assert.Equal((0, 0, 0, 0, 0), (look.EssaysHandedIn, look.TranslationsHandedIn, look.ActiveDays, look.Bands.Count, look.Habits.Count));
        Assert.Equal((0, 0, 0, 0), (look.Words, look.WordsDue, look.WordReviewsThisWeek, look.RememberedThisWeek));
    }

    [Fact]
    public void CountsPagesBandsAndOnlyTheNewestReviewPerPage()
    {
        var first = HandedIn(PieceKind.Essay, "夜市", "People enjoys it. It bring joy.");
        var second = HandedIn(PieceKind.Translation, "", "The festival matter.");
        _book.NewPage(PieceKind.Essay);
        _book.SaveReview(first.Id, Marks(8, ("People enjoys", "People enjoy", "must", "语法")), "t");
        _book.SaveReview(first.Id, Marks(11, ("People enjoys", "People enjoy", "must", "语法"), ("It bring", "It brings", "must", "语法")), "t");
        _book.SaveReview(second.Id, Marks(5, ("festival matter", "festival matters", "must", "语法"), ("The festival", "This festival", "maybe", "用词")), "t");

        var look = _book.LookBack();

        Assert.Equal((1, 1), (look.EssaysHandedIn, look.TranslationsHandedIn));
        Assert.Equal(1, look.ActiveDays);
        Assert.Equal([(PieceKind.Essay, 11, "夜市"), (PieceKind.Translation, 5, "task")], look.Bands.Select(b => (b.Kind, b.Band, b.Title)));
        var habit = Assert.Single(look.Habits);
        Assert.Equal(("语法", 3), (habit.Kind, habit.Count));
        Assert.Equal(("festival matter", "festival matters"), (habit.Quote, habit.Fix));
        Assert.Equal((0, 0, 4), (look.Taken, look.Left, look.Undecided));
    }

    [Fact]
    public void CountsDecisionsAndWordReviews()
    {
        var page = HandedIn(PieceKind.Essay, "t", "People enjoys it.");
        var review = _book.SaveReview(page.Id, Marks(11, ("People enjoys", "People enjoy", "must", "语法"), ("it", "this", "maybe", "用词")), "t");
        _book.Decide(review.Id, 1, Decision.Taken);
        _book.Decide(review.Id, 2, Decision.Left);
        var a = _book.AddWord("团聚", "reunite", "", null);
        var b = _book.AddWord("尽量", "try one's best", "", null);
        _book.ReviewWord(a.Id, Recall.Remembered);
        _book.ReviewWord(b.Id, Recall.Forgot);

        var look = _book.LookBack();

        Assert.Equal((1, 1, 0), (look.Taken, look.Left, look.Undecided));
        Assert.Equal((2, 2, 1), (look.Words, look.WordReviewsThisWeek, look.RememberedThisWeek));
    }
}
