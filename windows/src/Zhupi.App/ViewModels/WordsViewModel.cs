using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

public sealed record WordLine(string Id, string Front, string Back, string Box, string Due);

/// <summary>The word book: a list on the left page, one review card at a time on the right.</summary>
public sealed partial class WordsViewModel : ObservableObject
{
    private readonly Notebook _book;
    private readonly TimeProvider _clock;
    private readonly Action<string> _say;

    public WordsViewModel(Notebook book, TimeProvider clock, Action<string> say)
    {
        _book = book;
        _clock = clock;
        _say = say;
        Search = "";
        NewFront = NewBack = NewNote = "";
        Summary = "";
        DoneNote = "";
    }

    public ObservableCollection<WordLine> Lines { get; } = [];

    [ObservableProperty]
    public partial string Search { get; set; }

    [ObservableProperty]
    public partial string NewFront { get; set; }

    [ObservableProperty]
    public partial string NewBack { get; set; }

    [ObservableProperty]
    public partial string NewNote { get; set; }

    [ObservableProperty]
    public partial string Summary { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasCard), nameof(NoCard))]
    public partial WordCard? Current { get; set; }

    [ObservableProperty]
    public partial bool IsFlipped { get; set; }

    [ObservableProperty]
    public partial string DoneNote { get; set; }

    public bool HasCard => Current is not null;

    public bool NoCard => Current is null;

    public string BoxText => Current is { } c ? $"第 {c.Box} 格 · 复习过 {c.Reviews} 次" : "";

    partial void OnSearchChanged(string value) => RefreshList();

    partial void OnCurrentChanged(WordCard? value) => OnPropertyChanged(nameof(BoxText));

    public void Refresh()
    {
        RefreshList();
        NextCard();
    }

    [RelayCommand]
    private void Add()
    {
        try
        {
            _book.AddWord(NewFront, NewBack, NewNote, null);
            NewFront = NewBack = NewNote = "";
            _say("收进单词本了，马上就能复习");
            Refresh();
        }
        catch (NotebookException e)
        {
            _say(e.Message);
        }
    }

    [RelayCommand]
    private void Flip() => IsFlipped = true;

    [RelayCommand]
    private void Forgot() => Answer(Recall.Forgot);

    [RelayCommand]
    private void Fuzzy() => Answer(Recall.Fuzzy);

    [RelayCommand]
    private void Remembered() => Answer(Recall.Remembered);

    [RelayCommand]
    private void RemoveCurrent()
    {
        if (Current is null) return;
        _book.RemoveWord(Current.Id);
        _say("这一条从单词本里划掉了");
        Refresh();
    }

    private void Answer(Recall recall)
    {
        if (Current is null) return;
        var after = _book.ReviewWord(Current.Id, recall);
        _say(recall switch
        {
            Recall.Remembered => $"记得！放进第 {after.Box} 格，{Gap(after.DueAt)}再见",
            Recall.Fuzzy => $"有点模糊，{Gap(after.DueAt)}再来",
            _ => "没关系，十分钟后再考一次",
        });
        Refresh();
    }

    private void NextCard()
    {
        Current = _book.DueWords().FirstOrDefault();
        IsFlipped = false;
        if (Current is null)
        {
            var next = _book.Words().FirstOrDefault();
            DoneNote = next is null ? "单词本还是空的：在批注或翻译提示上点「收进单词本」，或者在左边手动加一条。"
                : $"今天要复习的都复习完了。下一张在 {next.DueAt.ToLocalTime():M月d日 HH:mm}。";
        }
    }

    private void RefreshList()
    {
        var now = _clock.GetUtcNow();
        Lines.Clear();
        var all = _book.Words(Search);
        foreach (var w in all)
        {
            Lines.Add(new WordLine(w.Id, w.Front, w.Back, new string('●', w.Box) + new string('○', WordSchedule.LastBox - w.Box),
                w.IsDue(now) ? "该复习了" : w.DueAt.ToLocalTime().ToString("M月d日", CultureInfo.GetCultureInfo("zh-CN"))));
        }
        var total = _book.Words().Count;
        Summary = $"共 {total} 条 · 今天要复习 {_book.DueWords().Count} 条";
    }

    private string Gap(DateTimeOffset due)
    {
        var days = (due - _clock.GetUtcNow()).TotalDays;
        return days < 1 ? "过一会儿" : $"{Math.Round(days):0} 天后";
    }
}
