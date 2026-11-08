using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

/// <summary>The open page. Typing is written into the notebook shortly after the learner pauses.</summary>
public sealed partial class PageViewModel : ObservableObject
{
    private readonly Notebook _book;
    private readonly TeacherSettings _teacher;
    private readonly Action<string> _say;
    private readonly DispatcherTimer _pause = new() { Interval = TimeSpan.FromMilliseconds(700) };
    private Piece _page;
    private bool _loading;
    private CancellationTokenSource? _asking;

    public PageViewModel(Notebook book, Piece page, TeacherSettings teacher, Action<string> say)
    {
        _book = book;
        _teacher = teacher;
        _say = say;
        _page = page;
        _pause.Tick += (_, _) => Save();
        _loading = true;
        Title = Task = Text = "";
        SaveNote = "";
        Pieces = [];
        Load(page);
    }

    /// <summary>Raised when the page changed in the notebook, so the table of contents can refresh.</summary>
    public event Action? Saved;

    /// <summary>Raised when hand-in or rewrite moved to another page.</summary>
    public event Action<Piece>? Turned;

    [ObservableProperty]
    public partial string Title { get; set; }

    [ObservableProperty]
    public partial string Task { get; set; }

    [ObservableProperty]
    public partial string Text { get; set; }

    [ObservableProperty]
    public partial string SaveNote { get; set; }

    public ObservableCollection<CheckLine> Checks { get; } = [];

    public ObservableCollection<MarkCard> Marks { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasReview), nameof(NoReview), nameof(BandText), nameof(BandRange), nameof(Verdict),
        nameof(DimensionsText), nameof(TakeLabel), nameof(ShowChecks), nameof(ShowMarks))]
    public partial StoredReview? Review { get; set; }

    [ObservableProperty]
    public partial IReadOnlyList<TextPiece> Pieces { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NotAsking), nameof(ShowChecks))]
    [NotifyCanExecuteChangedFor(nameof(AskTeacherCommand), nameof(TakeFixesCommand), nameof(HandInOrRewriteCommand), nameof(SendQuestionCommand))]
    public partial bool IsAsking { get; set; }

    public bool NotAsking => !IsAsking;

    public bool HasReview => Review is not null;

    public bool NoReview => Review is null;

    /// <summary>Pencil checks belong to drafting; a reviewed page shows the teacher's notes instead.</summary>
    public bool ShowChecks => IsDraft;

    public bool AwaitsTeacher => IsHandedIn && Review is null;

    public string BandText => Review?.Band.ToString(CultureInfo.InvariantCulture) ?? "";

    public string BandRange => Review?.Band switch
    {
        2 => "模拟 · 1–3 分",
        5 => "模拟 · 4–6 分",
        8 => "模拟 · 7–9 分",
        11 => "模拟 · 10–12 分",
        14 => "模拟 · 13–15 分",
        _ => "",
    };

    public string Verdict => Review is { Verdict.Length: > 0 } r ? "总评：" + r.Verdict : "";

    public string DimensionsText => Review is null ? "" : string.Join("　", Review.Dimensions.Select(d => $"{d.Name} {d.Level}"));

    public string TakeLabel => $"抄一遍改好的（采纳 {Marks.Count(m => m.IsTaken && m.IsPlaced)} 处）";

    public string TeacherNote => $"老师：{_teacher.Label}";

    public string Id => _page.Id;

    public bool IsEssay => _page.Kind == PieceKind.Essay;

    public bool IsHandedIn => _page.IsHandedIn;

    public bool IsDraft => !_page.IsHandedIn;

    public string Header => string.Create(CultureInfo.InvariantCulture,
        $"No. {_page.Number:00} · {_page.UpdatedAt.ToLocalTime():M月d日} · {(_page.IsHandedIn ? "已交" : _page.Revises is null ? "草稿" : "重写稿")}");

    public string TaskLabel => IsEssay ? "题目" : "中文原文";

    // A handed-in page is a record, so empty fields stay empty instead of inviting input.
    public string TaskHint => IsHandedIn ? "" : IsEssay ? "把题目贴在这里：要求、给定句、字数…" : "把要翻译的中文段落贴在这里…";

    public string TitleHint => IsHandedIn ? "" : IsEssay ? "这篇作文的名字" : "这段翻译的名字";

    public string TextHint => IsHandedIn ? "" : IsEssay ? "Write your first sentence here…" : "Translate the passage here…";

    public string ActionLabel => IsHandedIn ? "重写一稿" : "交稿";

    public string CheckHeading => IsHandedIn ? "这一页已经交了" : "交之前自己查一查";

    public int WordCount => SelfCheck.Words(Text);

    public WordTarget Target => SelfCheck.EssayTarget(Task);

    /// <summary>Fill of the word ruler, where 1.0 is the top of the target range.</summary>
    public double WordProgress => IsEssay ? Math.Min(1.0, WordCount / (double)Target.Max) : 0;

    /// <summary>Where the lower bound sits on the 300 px word ruler.</summary>
    public double TargetStartX => 300.0 * Target.Min / Target.Max - 1;

    public string WordNote => IsEssay ? $"目标 {Target.Min}–{Target.Max}" : "";

    [RelayCommand]
    public void Save()
    {
        _pause.Stop();
        if (_page.IsHandedIn) return;
        try
        {
            var saved = _book.Write(_page.Id, Title, Task, Text);
            if (saved.UpdatedAt != _page.UpdatedAt)
            {
                _page = saved;
                Saved?.Invoke();
            }
            SaveNote = $"已记在本子上 {_page.UpdatedAt.ToLocalTime():HH:mm}";
        }
        catch (NotebookException e)
        {
            SaveNote = "没记上，内容还在窗口里";
            _say(e.Message);
        }
    }

    /// <summary>Sends the handed-in page to the configured teacher once; nothing is retried automatically.</summary>
    [RelayCommand(CanExecute = nameof(NotAsking))]
    private async Task AskTeacher()
    {
        if (!_page.IsHandedIn) return;
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
        IsAsking = true;
        _asking = new CancellationTokenSource();
        try
        {
            var reply = await teacher.AskAsync(
                MarkingProtocol.Instruction(_page.Kind), MarkingProtocol.Material(_page.Kind, _page.Task, _page.Text), jsonReply: true,
                _asking.Token);
            var review = _book.SaveReview(_page.Id, MarkingProtocol.Read(reply), _teacher.Label);
            ShowReview(review);
            _say($"老师批完了：{review.Marks.Count} 处批注");
            Saved?.Invoke();
        }
        catch (OperationCanceledException)
        {
            _say("已经叫停，这次没有保存");
        }
        catch (Exception e) when (e is TeacherException or NotebookException or Zhupi.Core.ChatGpt.ChatGptException)
        {
            _say(e.Message);
        }
        finally
        {
            _asking.Dispose();
            _asking = null;
            IsAsking = false;
        }
    }

    [RelayCommand]
    private void StopAsking() => _asking?.Cancel();

    [RelayCommand(CanExecute = nameof(NotAsking))]
    private void TakeFixes()
    {
        try
        {
            var next = _book.RewriteWithTaken(_page.Id);
            _say($"按采纳的改法抄出了新的一页 No.{next.Number:00}，原稿和批改都留着");
            Turned?.Invoke(next);
        }
        catch (NotebookException e)
        {
            _say(e.Message);
        }
    }

    [RelayCommand(CanExecute = nameof(NotAsking))]
    private void HandInOrRewrite()
    {
        Save();
        try
        {
            var next = _page.IsHandedIn ? _book.Rewrite(_page.Id) : _book.HandIn(_page.Id);
            _say(_page.IsHandedIn ? $"从 No.{_page.Number:00} 抄出了新的一页，原稿留着" : "交了。这一页从现在起不能再改");
            Turned?.Invoke(next);
        }
        catch (NotebookException e)
        {
            _say(e.Message);
        }
    }

    private void Load(Piece page)
    {
        _page = page;
        _loading = true;
        Title = page.Title;
        Task = page.Task;
        Text = page.Text;
        _loading = false;
        SaveNote = page.IsHandedIn ? $"交于 {page.HandedInAt!.Value.ToLocalTime():M月d日 HH:mm}" : $"已记在本子上 {page.UpdatedAt.ToLocalTime():HH:mm}";
        RefreshChecks();
        LoadReference(page);
        IsEditingSource = false;
        RefreshSentences();
        ShowReview(page.IsHandedIn ? _book.LatestReview(page.Id) : null);
    }

    private void ShowReview(StoredReview? review)
    {
        Marks.Clear();
        if (review is not null)
        {
            foreach (var placed in review.Marks) Marks.Add(new MarkCard(placed, Decide, OpenMarkThread, CollectMark));
        }
        IsThreadOpen = false;
        Review = review;
        Pieces = Split(_page.Text, Marks.Select(m => m.Placed with { Decision = m.Decision }));
        OnPropertyChanged(nameof(AwaitsTeacher));
    }

    /// <summary>The learner's phrase goes on the front, the teacher's fix on the back, the reason as a note.</summary>
    private void CollectMark(int number)
    {
        if (Marks.FirstOrDefault(m => m.Number == number) is not { } mark) return;
        try
        {
            _book.AddWord(mark.Quote, mark.Fix, mark.Why, _page.Id);
            _say($"「{mark.Fix}」收进单词本了");
        }
        catch (NotebookException e)
        {
            _say(e.Message);
        }
    }

    private void Decide(int number, Decision decision)
    {
        if (Review is null) return;
        try
        {
            _book.Decide(Review.Id, number, decision);
        }
        catch (NotebookException e)
        {
            _say(e.Message);
            return;
        }
        // The card updates itself after this returns; rebuild the marked text from the new choice.
        Pieces = Split(_page.Text, Marks.Select(m => m.Number == number ? m.Placed with { Decision = decision } : m.Placed with { Decision = m.Decision }));
        OnPropertyChanged(nameof(TakeLabel));
    }

    /// <summary>Cuts the text into plain runs and marked runs, in order; overlapping marks keep the first.</summary>
    private static IReadOnlyList<TextPiece> Split(string text, IEnumerable<PlacedMark> marks)
    {
        var pieces = new List<TextPiece>();
        var at = 0;
        foreach (var m in marks.Where(m => m.IsPlaced).OrderBy(m => m.Start))
        {
            if (m.Start!.Value < at) continue;
            if (m.Start.Value > at) pieces.Add(new TextPiece(text[at..m.Start.Value], null));
            pieces.Add(new TextPiece(text.Substring(m.Start.Value, m.Length), m));
            at = m.Start.Value + m.Length;
        }
        if (at < text.Length) pieces.Add(new TextPiece(text[at..], null));
        return pieces;
    }

    partial void OnTitleChanged(string value) => Edited();

    partial void OnTaskChanged(string value)
    {
        Edited();
        OnPropertyChanged(nameof(Target));
        OnPropertyChanged(nameof(TargetStartX));
        OnPropertyChanged(nameof(WordProgress));
        OnPropertyChanged(nameof(WordNote));
    }

    partial void OnTextChanged(string value)
    {
        Edited();
        OnPropertyChanged(nameof(WordCount));
        OnPropertyChanged(nameof(WordProgress));
        OnPropertyChanged(nameof(TranslatedCount));
    }

    private void Edited()
    {
        if (_loading) return;
        RefreshChecks();
        if (_page.IsHandedIn) return;
        SaveNote = "正在记…";
        _pause.Stop();
        _pause.Start();
    }

    private void RefreshChecks()
    {
        Checks.Clear();
        foreach (var line in IsEssay ? SelfCheck.Essay(Task, Text) : SelfCheck.Translation(Task, Text)) Checks.Add(line);
    }
}
