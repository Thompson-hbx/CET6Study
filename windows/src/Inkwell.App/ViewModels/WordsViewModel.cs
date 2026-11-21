using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Inkwell.Core;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.ViewModels;

public sealed record WordLine(WordCard Card, string Box, string Due);

/// <summary>The word book: a list on the left page, one review card at a time on the right.</summary>
public sealed partial class WordsViewModel : ObservableObject
{
    private static readonly TimeSpan ActionCooldown = TimeSpan.FromMilliseconds(350);

    private readonly Notebook _book;
    private readonly TimeProvider _clock;
    private readonly Action<string> _say;
    private DateTimeOffset _flippedAt = DateTimeOffset.MinValue;
    private DateTimeOffset _removedAt = DateTimeOffset.MinValue;

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
    public partial bool OnlyDue { get; set; }

    [ObservableProperty]
    public partial string ListNote { get; private set; } = "";

    [ObservableProperty]
    public partial string Notice { get; private set; } = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsEditing), nameof(ShowReview), nameof(ShowDone))]
    [NotifyCanExecuteChangedFor(nameof(EditCommand), nameof(FlipCommand), nameof(ForgotCommand), nameof(FuzzyCommand), nameof(RememberedCommand), nameof(RemoveCurrentCommand))]
    public partial WordCard? Editing { get; private set; }

    [ObservableProperty]
    public partial string EditFront { get; set; } = "";

    [ObservableProperty]
    public partial string EditBack { get; set; } = "";

    [ObservableProperty]
    public partial string EditNote { get; set; } = "";

    [ObservableProperty]
    public partial string EditError { get; private set; } = "";

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(AddCommand))]
    public partial string NewFront { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(AddCommand))]
    public partial string NewBack { get; set; }

    [ObservableProperty]
    public partial string NewNote { get; set; }

    [ObservableProperty]
    public partial string Summary { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasCard), nameof(NoCard), nameof(ShowReview), nameof(ShowDone))]
    [NotifyCanExecuteChangedFor(nameof(FlipCommand), nameof(ForgotCommand), nameof(FuzzyCommand), nameof(RememberedCommand), nameof(RemoveCurrentCommand))]
    public partial WordCard? Current { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(FlipCommand), nameof(ForgotCommand), nameof(FuzzyCommand), nameof(RememberedCommand))]
    public partial bool IsFlipped { get; set; }

    [ObservableProperty]
    public partial string DoneNote { get; set; }

    public bool HasCard => Current is not null;

    public bool NoCard => Current is null;

    public bool IsEditing => Editing is not null;
    public bool ShowReview => HasCard && !IsEditing;
    public bool ShowDone => NoCard && !IsEditing;

    public string BoxText => Current is { } c ? $"第 {c.Box} 格 · 复习过 {c.Reviews} 次" : "";

    partial void OnSearchChanged(string value) => RefreshList();

    partial void OnOnlyDueChanged(bool value) => RefreshList();

    partial void OnCurrentChanged(WordCard? value) => OnPropertyChanged(nameof(BoxText));

    public void Refresh()
    {
        RefreshList();
        NextCard();
    }

    private bool CanAdd() => !string.IsNullOrWhiteSpace(NewFront) && !string.IsNullOrWhiteSpace(NewBack);

    [RelayCommand(CanExecute = nameof(CanAdd))]
    private void Add()
    {
        if (!CanAdd()) return;
        try
        {
            _book.AddWord(NewFront, NewBack, NewNote, null);
            NewFront = NewBack = NewNote = "";
            Report("收进单词本了，马上就能复习");
            Refresh();
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Report(PageViewModel.DbError(e));
        }
    }

    private bool CanEdit(WordCard? card) => card is not null && !IsEditing;

    [RelayCommand(CanExecute = nameof(CanEdit))]
    private void Edit(WordCard? card)
    {
        if (card is null || IsEditing) return;
        EditFront = card.Front;
        EditBack = card.Back;
        EditNote = card.Note;
        EditError = "";
        Notice = "";
        Editing = card;
    }

    [RelayCommand]
    private void SaveEdit()
    {
        if (Editing is not { } card) return;
        WordCard saved;
        try
        {
            saved = _book.UpdateWord(card.Id, EditFront, EditBack, EditNote);
        }
        catch (NotebookException e)
        {
            EditError = e.Message;
            return;
        }
        catch (SqliteException e)
        {
            EditError = $"词条保存失败（SQLite {e.SqliteErrorCode}），可再试一次。";
            return;
        }

        if (Current?.Id == saved.Id) Current = saved;
        CancelEdit();
        RefreshList();
        Report("已保存词条");
    }

    [RelayCommand]
    private void CancelEdit() => Editing = null;

    private bool CanFlip() => Current is not null && !IsEditing && !IsFlipped;

    private bool CanAnswer() => Current is not null && !IsEditing && IsFlipped;

    private bool CanRemove() => Current is not null && !IsEditing;

    [RelayCommand(CanExecute = nameof(CanFlip))]
    private void Flip()
    {
        if (!CanFlip()) return;
        _flippedAt = _clock.GetUtcNow();
        IsFlipped = true;
    }

    [RelayCommand(CanExecute = nameof(CanAnswer))]
    private void Forgot() => Answer(Recall.Forgot);

    [RelayCommand(CanExecute = nameof(CanAnswer))]
    private void Fuzzy() => Answer(Recall.Fuzzy);

    [RelayCommand(CanExecute = nameof(CanAnswer))]
    private void Remembered() => Answer(Recall.Remembered);

    [RelayCommand(CanExecute = nameof(CanRemove))]
    private void RemoveCurrent()
    {
        if (!CanRemove() || Current is null) return;
        var now = _clock.GetUtcNow();
        if (now - _removedAt < ActionCooldown) return;
        _removedAt = now;
        try
        {
            _book.RemoveWord(Current.Id);
            Report("这一条从单词本里划掉了");
            Refresh();
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Report(PageViewModel.DbError(e));
        }
    }

    private void Answer(Recall recall)
    {
        if (!CanAnswer() || Current is null) return;
        if (_clock.GetUtcNow() - _flippedAt < ActionCooldown) return;
        try
        {
            var after = _book.ReviewWord(Current.Id, recall);
            Report(recall switch
            {
                Recall.Remembered => $"记得！放进第 {after.Box} 格，{Gap(after.DueAt)}再见",
                Recall.Fuzzy => $"有点模糊，{Gap(after.DueAt)}再来",
                _ => "没关系，十分钟后再考一次",
            });
            Refresh();
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Report(PageViewModel.DbError(e));
        }
    }

    private void NextCard()
    {
        try
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
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Report(PageViewModel.DbError(e));
        }
    }

    private void RefreshList()
    {
        try
        {
            var now = _clock.GetUtcNow();
            var hasSearch = !string.IsNullOrWhiteSpace(Search);
            var matches = _book.Words(Search);
            var all = hasSearch ? _book.Words() : matches;
            Lines.Clear();
            foreach (var card in matches)
            {
                var due = card.IsDue(now);
                if (OnlyDue && !due) continue;
                Lines.Add(new WordLine(card, new string('●', card.Box) + new string('○', WordSchedule.LastBox - card.Box),
                    due ? "该复习了" : card.DueAt.ToLocalTime().ToString("M月d日", CultureInfo.GetCultureInfo("zh-CN"))));
            }
            Summary = $"共 {all.Count} 条 · {all.Count(card => card.IsDue(now))} 条待复习";
            ListNote = Lines.Count == 0 && (OnlyDue || hasSearch) ? "没有匹配的词条" : "";
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Report(PageViewModel.DbError(e));
        }
    }

    private void Report(string message)
    {
        Notice = message;
        _say(message);
    }

    private string Gap(DateTimeOffset due)
    {
        var zone = _clock.LocalTimeZone;
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(_clock.GetUtcNow(), zone).Date);
        var target = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(due, zone).Date);
        var days = target.DayNumber - today.DayNumber;
        return days < 1 ? "过一会儿" : $"{days} 天后";
    }
}
