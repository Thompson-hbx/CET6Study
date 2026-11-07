using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;
using Zhupi.Core.ChatGpt;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

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
    [NotifyCanExecuteChangedFor(nameof(AskWordsCommand), nameof(AskFrameCommand))]
    public partial bool IsHinting { get; set; }

    public bool NotHinting => !IsHinting;

    public string TranslatedCount => Sentences.Count == 0
        ? ""
        : $"写到第 {Math.Min(SelfCheck.EnglishSentences(Text), Sentences.Count)} 句，共 {Sentences.Count} 句（按句号估计）";

    [RelayCommand]
    private void SelectSentence(SourceSentence sentence)
    {
        if (SelectedSentence is { } old) old.IsSelected = false;
        sentence.IsSelected = true;
        SelectedSentence = sentence;
        LoadHints();
    }

    [RelayCommand]
    private void ToggleSourceEditing()
    {
        IsEditingSource = !IsEditingSource;
        if (!IsEditingSource) RefreshSentences();
    }

    private bool CanHint() => SelectedSentence is not null && !IsHinting && IsDraft;

    [RelayCommand(CanExecute = nameof(CanHint))]
    private Task AskWords() => AskHint(HintKind.Words);

    [RelayCommand(CanExecute = nameof(CanHint))]
    private Task AskFrame() => AskHint(HintKind.Frame);

    [RelayCommand]
    private void StopHint() => _hinting?.Cancel();

    /// <summary>Rebuilds the numbered sentences from the source, keeping the selection on the same number when it still exists.</summary>
    private void RefreshSentences()
    {
        var selected = SelectedSentence?.Number;
        Sentences.Clear();
        var number = 0;
        foreach (var text in SourceText.Sentences(Task)) Sentences.Add(new SourceSentence(++number, text));
        SelectedSentence = Sentences.FirstOrDefault(s => s.Number == selected);
        if (SelectedSentence is { } s) s.IsSelected = true;
        if (Sentences.Count == 0) IsEditingSource = IsTranslationDraft;
        OnPropertyChanged(nameof(ShowSentences));
        OnPropertyChanged(nameof(TranslatedCount));
        LoadHints();
    }

    private void LoadHints()
    {
        SentenceHints.Clear();
        if (SelectedSentence is not { } s) return;
        foreach (var hint in _book.Hints(_page.Id, s.Number, s.Text)) SentenceHints.Add(hint);
    }

    /// <summary>One hint for the selected sentence; stored only when the full reply arrives.</summary>
    private async Task AskHint(HintKind kind)
    {
        if (SelectedSentence is not { } sentence) return;
        Save();
        using var http = ChatTeacher.NewHttpClient();
        ITeacher teacher;
        try
        {
            teacher = _teacher.CreateTeacher(http);
        }
        catch (TeacherException e)
        {
            _say(e.Message);
            return;
        }
        IsHinting = true;
        _hinting = new CancellationTokenSource();
        try
        {
            var sentences = Sentences.Select(s => s.Text).ToList();
            var text = await teacher.AskAsync(HintProtocol.Instruction(kind), HintProtocol.Material(sentences, sentence.Number),
                jsonReply: false, _hinting.Token);
            _book.SaveHint(_page.Id, sentence.Number, sentence.Text, kind, text, _teacher.Label);
            if (SelectedSentence == sentence) LoadHints();
        }
        catch (OperationCanceledException)
        {
            _say("已经叫停，这次提示没有记下");
        }
        catch (Exception e) when (e is TeacherException or NotebookException or ChatGptException)
        {
            _say(e.Message);
        }
        finally
        {
            _hinting.Dispose();
            _hinting = null;
            IsHinting = false;
        }
    }
}
