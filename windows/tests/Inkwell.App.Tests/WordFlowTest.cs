using System.IO;
using Inkwell.App.ViewModels;
using Inkwell.Core;

namespace Inkwell.App.Tests;

public sealed class WordFlowTest : IDisposable
{
    private readonly DirectoryInfo _directory = Directory.CreateTempSubdirectory("inkwell-word-flow-");
    private readonly ManualClock _clock = new();
    private readonly Notebook _book;

    public WordFlowTest() => _book = Notebook.Open(Path.Combine(_directory.FullName, "study.db"), _clock);

    [Fact]
    public void SearchAndDueFilterWorkTogetherWithoutResettingTheReviewCard()
    {
        var current = _book.AddWord("read", "阅读", "", null);
        _clock.Now = _clock.Now.AddSeconds(1);
        var second = _book.AddWord("review", "复习", "读书", null);
        var later = _book.AddWord("repeat", "重复", "", null);
        later = _book.ReviewWord(later.Id, Recall.Remembered);
        var words = View();

        words.FlipCommand.Execute(null);
        words.OnlyDue = true;
        Assert.Equal(2, words.Lines.Count);
        Assert.DoesNotContain(words.Lines, line => line.Card.Id == later.Id);
        words.Search = "读书";
        Assert.Equal(second.Id, Assert.Single(words.Lines).Card.Id);
        words.Search = "repeat";
        Assert.Empty(words.Lines);
        Assert.Equal("没有匹配的词条", words.ListNote);
        Assert.Equal(current.Id, words.Current?.Id);
        Assert.True(words.IsFlipped);
        Assert.Equal("共 3 条 · 2 条待复习", words.Summary);

        _clock.Now = later.DueAt;
        words.Refresh();

        Assert.Equal(later.Id, Assert.Single(words.Lines).Card.Id);
        Assert.Empty(words.ListNote);
        Assert.Equal("共 3 条 · 3 条待复习", words.Summary);
    }

    [Fact]
    public void EditingADueOrFutureCardKeepsTheReviewPosition()
    {
        var current = _book.AddWord("read", "阅读", "", null);
        var later = _book.AddWord("repeat", "重复", "", null);
        later = _book.ReviewWord(later.Id, Recall.Remembered);
        var words = View();
        words.FlipCommand.Execute(null);

        words.EditCommand.Execute(words.Lines.Single(line => line.Card.Id == later.Id).Card);
        Assert.True(words.IsEditing);
        Assert.False(words.ShowReview);
        words.EditFront = "repeat again";
        words.EditBack = "再重复一次";
        words.EditNote = "记下例句";
        words.SaveEditCommand.Execute(null);

        var updated = words.Lines.Single(line => line.Card.Id == later.Id).Card;
        Assert.Equal(later with { Front = "repeat again", Back = "再重复一次", Note = "记下例句" }, updated);
        Assert.False(words.IsEditing);
        Assert.True(words.ShowReview);
        Assert.True(words.IsFlipped);
        Assert.Equal(current.Id, words.Current?.Id);
        Assert.Equal("已保存词条", words.Notice);

        words.EditCommand.Execute(words.Current);
        words.EditBack = "读书";
        words.SaveEditCommand.Execute(null);

        Assert.Equal(current with { Back = "读书" }, words.Current);
        Assert.True(words.IsFlipped);
        Assert.True(words.ShowReview);
    }

    [Fact]
    public void RejectedEditKeepsTheInputUntilTheUserCancels()
    {
        var original = _book.AddWord("read", "阅读", "原备注", null);
        var other = _book.AddWord("repeat", "重复", "", null);
        var words = View();

        words.EditCommand.Execute(original);
        words.EditFront = other.Front;
        words.EditBack = other.Back;
        words.EditNote = "尚未保存";
        words.SaveEditCommand.Execute(null);

        Assert.Equal(original.Id, words.Editing?.Id);
        Assert.Equal("尚未保存", words.EditNote);
        Assert.Equal("单词本里已经有这一条了。", words.EditError);
        Assert.False(words.EditCommand.CanExecute(other));

        words.CancelEditCommand.Execute(null);

        Assert.False(words.IsEditing);
        Assert.True(words.ShowReview);
        Assert.Contains(original, _book.Words());
        Assert.True(words.EditCommand.CanExecute(other));
    }

    [Fact]
    public void GapUsesLocalCalendarDaysAcrossLateNightAndDstSpringForward_NewL01()
    {
        var rule = TimeZoneInfo.AdjustmentRule.CreateAdjustmentRule(
            new DateTime(2026, 1, 1),
            new DateTime(2026, 12, 31),
            TimeSpan.FromHours(1),
            TimeZoneInfo.TransitionTime.CreateFloatingDateRule(new DateTime(1, 1, 1, 2, 0, 0), 3, 2, DayOfWeek.Sunday),
            TimeZoneInfo.TransitionTime.CreateFloatingDateRule(new DateTime(1, 1, 1, 2, 0, 0), 11, 1, DayOfWeek.Sunday));
        var dstZone = TimeZoneInfo.CreateCustomTimeZone("TestEastern", TimeSpan.FromHours(-5), "TestEastern", "EST", "EDT", [rule]);
        _clock.Zone = dstZone;
        // 2026-03-07 23:30 EST (-05:00) -> 2026-03-08 04:30 UTC; DST springs forward on 2026-03-08.
        _clock.Now = new DateTimeOffset(2026, 3, 7, 23, 30, 0, TimeSpan.FromHours(-5));

        var book = Notebook.Open(Path.Combine(_directory.FullName, "dst.db"), _clock);
        try
        {
            var card = book.AddWord("spring", "春天", "", null);
            var words = new WordsViewModel(book, _clock, _ => { });
            words.Refresh();
            words.FlipCommand.Execute(null);
            _clock.Now = _clock.Now.AddMilliseconds(400);

            // Box 0 + Fuzzy -> 1 day later (2026-03-08 00:00 EST = 05:00 UTC)
            words.FuzzyCommand.Execute(null);
            Assert.Contains("1 天后再来", words.Notice);

            // Next card: Box 1 + Remembered -> 2 days later (2026-03-09 00:00 EDT = 04:00 UTC; only 23.5h from 03-07 23:30!)
            var second = book.AddWord("autumn", "秋天", "", null);
            second = book.ReviewWord(second.Id, Recall.Fuzzy);
            _clock.Now = second.DueAt;
            words.Refresh();
            words.FlipCommand.Execute(null);
            _clock.Now = _clock.Now.AddMilliseconds(400);

            words.RememberedCommand.Execute(null);
            Assert.Contains("2 天后再见", words.Notice);
        }
        finally
        {
            book.Dispose();
        }
    }

    [Fact]
    public void CommandsEnforceCanExecuteAndActionCooldownDebounce()
    {
        var first = _book.AddWord("alpha", "甲", "", null);
        _clock.Now = _clock.Now.AddSeconds(1);
        var second = _book.AddWord("beta", "乙", "", null);
        var words = View();

        Assert.False(words.AddCommand.CanExecute(null));
        words.NewFront = "gamma";
        Assert.False(words.AddCommand.CanExecute(null));
        words.NewBack = "丙";
        Assert.True(words.AddCommand.CanExecute(null));

        Assert.True(words.FlipCommand.CanExecute(null));
        Assert.False(words.ForgotCommand.CanExecute(null));
        Assert.False(words.FuzzyCommand.CanExecute(null));
        Assert.False(words.RememberedCommand.CanExecute(null));
        Assert.True(words.RemoveCurrentCommand.CanExecute(null));

        words.FlipCommand.Execute(null);
        Assert.False(words.FlipCommand.CanExecute(null));
        Assert.True(words.FuzzyCommand.CanExecute(null));

        // Immediate double-click within 350ms cooldown is ignored.
        words.FuzzyCommand.Execute(null);
        Assert.Equal(first.Id, words.Current?.Id);

        _clock.Now = _clock.Now.AddMilliseconds(350);
        words.FuzzyCommand.Execute(null);
        Assert.Equal(second.Id, words.Current?.Id);

        // RemoveCurrent succeeds once, then immediate double-click within cooldown is ignored.
        words.RemoveCurrentCommand.Execute(null);
        Assert.DoesNotContain(_book.Words(), w => w.Id == second.Id);
        Assert.Equal(first.Id, Assert.Single(_book.Words()).Id);
        words.RemoveCurrentCommand.Execute(null);
        Assert.Equal(first.Id, Assert.Single(_book.Words()).Id);
    }

    public void Dispose()
    {
        _book.Dispose();
        _directory.Delete(recursive: true);
    }

    private WordsViewModel View()
    {
        var words = new WordsViewModel(_book, _clock, _ => { });
        words.Refresh();
        return words;
    }

    private sealed class ManualClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 11, 11, 0, 0, 0, TimeSpan.Zero);
        public TimeZoneInfo Zone { get; set; } = TimeZoneInfo.Utc;
        public override DateTimeOffset GetUtcNow() => Now;
        public override TimeZoneInfo LocalTimeZone => Zone;
    }
}
