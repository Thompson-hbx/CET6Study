namespace Zhupi.Core.Tests;

public sealed class WordBookTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-words-");
    private readonly ManualClock _clock = new();
    private readonly Notebook _book;

    public WordBookTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), _clock);

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public void GapsDoubleFromOneDay()
    {
        Assert.Equal([1, 2, 4, 8, 16, 32], Enumerable.Range(1, 6).Select(b => WordSchedule.Gap(b).TotalDays));
        var now = DateTimeOffset.UnixEpoch;
        Assert.Equal((3, now.AddDays(4)), WordSchedule.Next(2, Recall.Remembered, now));
        Assert.Equal((6, now.AddDays(32)), WordSchedule.Next(6, Recall.Remembered, now));
        Assert.Equal((4, now.AddDays(4)), WordSchedule.Next(4, Recall.Fuzzy, now));
        Assert.Equal((1, now.AddMinutes(10)), WordSchedule.Next(5, Recall.Forgot, now));
    }

    [Fact]
    public void NewCardsAreDueAndDuplicatesAreRefused()
    {
        var card = _book.AddWord(" reunite with ", "团聚", "", null);

        Assert.Equal(("reunite with", "团聚", 1), (card.Front, card.Back, card.Box));
        Assert.Equal([card.Id], _book.DueWords().Select(w => w.Id));
        Assert.Equal("单词本里已经有这一条了。", Assert.Throws<NotebookException>(() => _book.AddWord("reunite with", "团聚", "", null)).Message);
        Assert.Throws<NotebookException>(() => _book.AddWord(" ", "x", "", null));
    }

    [Fact]
    public void ReviewingMovesTheCardAndLeavesItUntilDue()
    {
        var card = _book.AddWord("团聚", "reunite with", "", null);

        var after = _book.ReviewWord(card.Id, Recall.Remembered);

        Assert.Equal((2, 1), (after.Box, after.Reviews));
        Assert.Empty(_book.DueWords());
        _clock.Advance(TimeSpan.FromDays(2));
        Assert.Single(_book.DueWords());
        _book.ReviewWord(card.Id, Recall.Forgot);
        Assert.Equal(1, _book.Words().Single().Box);
        _clock.Advance(TimeSpan.FromMinutes(11));
        Assert.Single(_book.DueWords());
    }

    [Fact]
    public void SearchAndRemove()
    {
        var a = _book.AddWord("团聚", "reunite with", "春节那篇", null);
        _book.AddWord("尽量", "try one's best to", "", null);

        Assert.Equal([a.Id], _book.Words("REUNITE").Select(w => w.Id));
        Assert.Equal([a.Id], _book.Words("春节").Select(w => w.Id));
        _book.ReviewWord(a.Id, Recall.Fuzzy);
        _book.RemoveWord(a.Id);
        Assert.Single(_book.Words());
    }

    [Fact]
    public void CardsCanPointBackToTheirPage()
    {
        var page = _book.NewPage(PieceKind.Translation);

        var card = _book.AddWord("团聚", "reunite with", "", page.Id);

        Assert.Equal(page.Id, _book.Words().Single(w => w.Id == card.Id).SourcePieceId);
    }

    [Fact]
    public void ReadsArrowLinesFromAHint()
    {
        const string hint = "无论身在何处 → wherever they are\n- 尽量 -> try one's best to（常接动词原形）\n“团聚”可以用 reunite\n团聚 => reunite with\n→ 没有中文";

        Assert.Equal(
            [("无论身在何处", "wherever they are"), ("尽量", "try one's best to"), ("团聚", "reunite with")],
            HintTerms.Read(hint));
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset _now = new(2026, 11, 7, 8, 0, 0, TimeSpan.Zero);

        public void Advance(TimeSpan by) => _now += by;

        public override DateTimeOffset GetUtcNow() => _now;
    }
}
