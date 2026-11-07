using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;
using Zhupi.Core.ChatGpt;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

/// <summary>Asking the teacher about one mark or the whole page; shown in the margin in place of the marks.</summary>
public sealed partial class PageViewModel
{
    private CancellationTokenSource? _answering;

    public ObservableCollection<Talk> Thread { get; } = [];

    /// <summary>Two ready-made questions for the open thread; tapping one fills the question box.</summary>
    public ObservableCollection<string> Starters { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowMarks))]
    public partial bool IsThreadOpen { get; set; }

    [ObservableProperty]
    public partial int? ThreadMark { get; set; }

    [ObservableProperty]
    public partial string ThreadTitle { get; set; } = "";

    [ObservableProperty]
    public partial string ThreadQuote { get; set; } = "";

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SendQuestionCommand))]
    public partial string Question { get; set; } = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NotAnswering))]
    [NotifyCanExecuteChangedFor(nameof(SendQuestionCommand), nameof(CloseThreadCommand))]
    public partial bool IsAnswering { get; set; }

    public bool NotAnswering => !IsAnswering;

    public bool ShowMarks => HasReview && !IsThreadOpen;

    public bool ThreadIsEmpty => Thread.Count == 0;

    private void OpenMarkThread(int number) => OpenThread(number);

    [RelayCommand]
    private void OpenPageThread() => OpenThread(null);

    private void OpenThread(int? mark)
    {
        if (Review is null) return;
        ThreadMark = mark;
        var placed = mark is { } n ? Review.Marks.FirstOrDefault(m => m.Mark.Number == n) : null;
        ThreadTitle = placed is null ? "问老师 · 整篇" : $"问老师 · {MarkCard.Circle(placed.Mark.Number)}";
        ThreadQuote = placed is null ? "" : $"「{placed.Mark.Quote}」→「{placed.Mark.Fix}」";
        Starters.Clear();
        foreach (var s in placed is null ? ["最该先改的是哪里？", "怎样冲到更高一档？"] : new[] { "为什么要这样改？", "换个说法行吗？" })
        {
            Starters.Add(s);
        }
        Thread.Clear();
        foreach (var talk in _book.Talks(Review.Id, mark)) Thread.Add(talk);
        OnPropertyChanged(nameof(ThreadIsEmpty));
        Question = "";
        IsThreadOpen = true;
    }

    [RelayCommand(CanExecute = nameof(NotAnswering))]
    private void CloseThread() => IsThreadOpen = false;

    [RelayCommand]
    private void UseStarter(string starter) => Question = starter;

    private bool CanSend() => !IsAnswering && !IsAsking && Question.Trim().Length > 0;

    /// <summary>Sends one question; the question and answer are stored together only after the full answer arrives.</summary>
    [RelayCommand(CanExecute = nameof(CanSend))]
    private async Task SendQuestion()
    {
        if (Review is not { } review) return;
        string question;
        try
        {
            question = FollowUpProtocol.CheckedQuestion(Question);
        }
        catch (TeacherException e)
        {
            _say(e.Message);
            return;
        }
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
        IsAnswering = true;
        _answering = new CancellationTokenSource();
        var mark = ThreadMark;
        try
        {
            var answer = await teacher.AskAsync(FollowUpProtocol.Instruction(_page.Kind),
                FollowUpProtocol.Material(_page, review, mark, Thread.ToList(), question), jsonReply: false, _answering.Token);
            var talk = _book.SaveTalk(review.Id, mark, question, answer, _teacher.Label);
            if (IsThreadOpen && ThreadMark == mark) Thread.Add(talk);
            OnPropertyChanged(nameof(ThreadIsEmpty));
            Question = "";
        }
        catch (OperationCanceledException)
        {
            _say("已经叫停，这个问题没有记下，可以再问一次");
        }
        catch (Exception e) when (e is TeacherException or NotebookException or ChatGptException)
        {
            _say(e.Message);
        }
        finally
        {
            _answering.Dispose();
            _answering = null;
            IsAnswering = false;
        }
    }

    [RelayCommand]
    private void StopAnswering() => _answering?.Cancel();
}
