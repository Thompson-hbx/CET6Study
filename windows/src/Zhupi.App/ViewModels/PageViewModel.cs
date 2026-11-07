using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

/// <summary>The open page. Typing is written into the notebook shortly after the learner pauses.</summary>
public sealed partial class PageViewModel : ObservableObject
{
    private readonly Notebook _book;
    private readonly Action<string> _say;
    private readonly DispatcherTimer _pause = new() { Interval = TimeSpan.FromMilliseconds(700) };
    private Piece _page;
    private bool _loading;

    public PageViewModel(Notebook book, Piece page, Action<string> say)
    {
        _book = book;
        _say = say;
        _page = page;
        _pause.Tick += (_, _) => Save();
        _loading = true;
        Title = Task = Text = "";
        SaveNote = "";
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

    public string CheckFootnote => IsHandedIn
        ? "想改就「重写一稿」：新的一页从这稿抄起，这页原样留着。"
        : "铅笔字只按题目要求自动核对，不花 AI 额度。";

    public int WordCount => SelfCheck.Words(Text);

    public WordTarget Target => SelfCheck.EssayTarget(Task);

    /// <summary>Fill of the word ruler, where 1.0 is the top of the target range.</summary>
    public double WordProgress => IsEssay ? Math.Min(1.0, WordCount / (double)Target.Max) : 0;

    /// <summary>Where the lower bound sits on the 300 px word ruler.</summary>
    public double TargetStartX => 300.0 * Target.Min / Target.Max - 1;

    public string WordNote => IsEssay ? $"目标 {Target.Min}–{Target.Max}" : "逐句对着原文写";

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

    [RelayCommand]
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
