using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Inkwell.Core;
using Inkwell.Core.Teacher;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.ViewModels;

/// <summary>One numbered source sentence on the left half of a translation draft.</summary>
public sealed partial class SourceSentence(int number, string text) : ObservableObject
{
    public int Number { get; } = number;

    public string Text { get; } = text;

    [ObservableProperty]
    public partial bool IsSelected { get; set; }
}

/// <summary>Translation drafts: numbered source on the left, the learner's text on the right, a hint note in the margin.</summary>
public sealed partial class PageViewModel
{
    private CancellationTokenSource? _hinting;

    public ObservableCollection<SourceSentence> Sentences { get; } = [];

    public ObservableCollection<SentenceHint> SentenceHints { get; } = [];

    /// <summary>Translation drafts use the two-column layout; everything else keeps the single sheet.</summary>
    public bool IsTranslationDraft => !IsEssay && IsDraft;

    public bool IsSingleSheet => !IsTranslationDraft;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowSentences))]
    [NotifyCanExecuteChangedFor(nameof(AskWordsCommand), nameof(AskFrameCommand))]
    public partial bool IsEditingSource { get; set; }

    public bool ShowSentences => !IsEditingSource && Sentences.Count > 0;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasSelectedSentence), nameof(SelectedHeading))]
    [NotifyCanExecuteChangedFor(nameof(AskWordsCommand), nameof(AskFrameCommand))]
    public partial SourceSentence? SelectedSentence { get; set; }

    public bool HasSelectedSentence => SelectedSentence is not null;

    public string SelectedHeading => SelectedSentence is { } s ? $"第 {s.Number} 句卡住了？" : "点左边的一句，再来问提示";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NotHinting))]
    [NotifyCanExecuteChangedFor(nameof(AskWordsCommand), nameof(AskFrameCommand), nameof(HandInOrRewriteCommand), nameof(ToggleSourceEditingCommand))]
    public partial bool IsHinting { get; set; }

    public bool NotHinting => !IsHinting;

    public string TranslatedCount => Sentences.Count == 0
        ? ""
        : $"写到第 {Math.Min(SelfCheck.EnglishSentences(Text), Sentences.Count)} 句，共 {Sentences.Count} 句";

    [RelayCommand]
    private void SelectSentence(SourceSentence sentence)
    {
        if (SelectedSentence is { } old) old.IsSelected = false;
        sentence.IsSelected = true;
        SelectedSentence = sentence;
        LoadHints();
    }

    [RelayCommand(CanExecute = nameof(NotHinting))]
    private void ToggleSourceEditing()
    {
        if (IsHinting) return;
        IsEditingSource = !IsEditingSource;
        if (!IsEditingSource) RefreshSentences();
    }

    private bool CanHint() => SelectedSentence is not null && !IsHinting && !IsEditingSource && IsDraft;

    [RelayCommand(CanExecute = nameof(CanHint))]
    private Task AskWords() => AskHint(HintKind.Words);

    [RelayCommand(CanExecute = nameof(CanHint))]
    private Task AskFrame() => AskHint(HintKind.Frame);

    [RelayCommand]
    private void StopHint() => _hinting?.Cancel();

    /// <summary>Every "中文 → English" line of the hint becomes a card; lines already in the book are skipped.</summary>
    [RelayCommand]
    private void CollectHint(SentenceHint hint)
    {
        var pairs = HintTerms.Read(hint.Text);
        if (pairs.Count == 0)
        {
            _say("这条提示里没有「中文 → 英文」的词条可收");
            return;
        }
        var added = 0;
        foreach (var (front, back) in pairs)
        {
            try
            {
                _book.AddWord(front, back, SelectedSentence?.Text ?? "", _page.Id);
                added++;
            }
            catch (NotebookException)
            {
                // Already in the book or too long: keep going with the rest.
            }
            catch (SqliteException e)
            {
                _say($"收进单词本失败（SQLite {e.SqliteErrorCode}），已保存 {added} 条。");
                return;
            }
        }
        _say(added == pairs.Count ? $"收进单词本 {added} 条" : $"收进单词本 {added} 条，另外 {pairs.Count - added} 条已经有了或太长");
    }

    /// <summary>Rebuilds the numbered sentences from the source, keeping the selection on the same number when it still exists.</summary>
    private void RefreshSentences()
    {
        var next = SourceText.Sentences(Task);
        if (!Sentences.Select(s => s.Text).SequenceEqual(next))
        {
            var selected = SelectedSentence?.Number;
            Sentences.Clear();
            var number = 0;
            foreach (var text in next) Sentences.Add(new SourceSentence(++number, text));
            SelectedSentence = Sentences.FirstOrDefault(s => s.Number == selected);
            if (SelectedSentence is { } s) s.IsSelected = true;
            LoadHints();
        }
        if (Sentences.Count == 0) IsEditingSource = IsTranslationDraft;
        OnPropertyChanged(nameof(ShowSentences));
        OnPropertyChanged(nameof(TranslatedCount));
    }

    private void LoadHints()
    {
        SentenceHints.Clear();
        if (SelectedSentence is not { } s) return;
        try
        {
            foreach (var hint in _book.Hints(_page.Id, s.Number, s.Text)) SentenceHints.Add(hint);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            _say(DbError(e));
        }
    }

    /// <summary>One hint for the selected sentence; stored only when the full reply arrives.</summary>
    private async Task AskHint(HintKind kind)
    {
        if (SelectedSentence is not { } sentence || !TrySave()) return;
        var sentences = Sentences.Select(s => s.Text).ToList();
        await UseTeacher(busy => IsHinting = busy, cancel => _hinting = cancel, "已经叫停，这次提示没有记下", async (teacher, label, _, cancel) =>
        {
            var text = await teacher.AskAsync(HintProtocol.Instruction(kind), HintProtocol.Material(sentences, sentence.Number),
                jsonReply: false, cancel);
            if (_abandoned) return;
            cancel.ThrowIfCancellationRequested();
            _book.SaveHint(_page.Id, sentence.Number, sentence.Text, kind, text, label);
            if (SelectedSentence == sentence) LoadHints();
            if (_closed || SelectedSentence != sentence) Say($"第 {sentence.Number} 句的提示回来了，已经记下");
            NotebookUpdated?.Invoke();
        });
    }
}
