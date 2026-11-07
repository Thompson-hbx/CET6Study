using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

/// <summary>One row on the shelf: the paper and a short mark for each part.</summary>
public sealed record PaperLine(string Id, string Label, string EssayMark, string TranslationMark, bool Unreviewed);

/// <summary>Past papers from content packs: a shelf on the left page, the chosen paper on the right.</summary>
public sealed partial class PapersViewModel : ObservableObject
{
    private readonly Notebook _book;
    private readonly Action<string> _say;
    private readonly Action<Piece> _open;
    private IReadOnlyList<PaperOnShelf> _shelf = [];
    private bool _syncing;

    public PapersViewModel(Notebook book, Action<string> say, Action<Piece> open)
    {
        _book = book;
        _say = say;
        _open = open;
        Summary = "";
    }

    public ObservableCollection<PaperLine> Lines { get; } = [];

    [ObservableProperty]
    public partial PaperLine? Selected { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasPaper), nameof(Label), nameof(SourceLine), nameof(ReviewNote), nameof(EssayTask),
        nameof(TranslationTask), nameof(EssayState), nameof(TranslationState), nameof(EssayAction), nameof(TranslationAction),
        nameof(EssayReference), nameof(TranslationReference), nameof(HasEssay), nameof(HasTranslation))]
    [NotifyCanExecuteChangedFor(nameof(StartEssayCommand), nameof(StartTranslationCommand))]
    public partial PaperOnShelf? Current { get; set; }

    [ObservableProperty]
    public partial string Summary { get; set; }

    public bool IsEmpty => Lines.Count == 0;

    public bool HasPaper => Current is not null;

    public string Label => Current?.Paper.Label ?? "";

    public string SourceLine => Current is { Paper: var p }
        ? $"来源：{p.SourceName}　·　{License(p.License)}　·　修订 {p.Revision}"
        : "";

    public string ReviewNote => Current is { Paper.Reviewed: false } ? "这套还没人工校对过，题目和原文可能有出入，做之前对照一下原卷。" : "";

    public bool HasEssay => Current?.Paper.HasEssay == true;

    public bool HasTranslation => Current?.Paper.HasTranslation == true;

    public string EssayTask => Current?.Paper.EssayTask ?? "";

    public string TranslationTask => Current?.Paper.ChineseText ?? "";

    public string EssayState => Current is { } c ? State(c.Essay) : "";

    public string TranslationState => Current is { } c ? State(c.Translation) : "";

    public string EssayAction => Current is { } c ? Action(c.Essay, "写这篇作文") : "";

    public string TranslationAction => Current is { } c ? Action(c.Translation, "做这段翻译") : "";

    public string EssayReference => Current is { Paper.ReferenceEssay.Length: > 0 } ? "附参考范文，交了之后可在页顶翻开对照" : "没有参考范文";

    public string TranslationReference => Current is { Paper.ReferenceTranslation.Length: > 0 } ? "附参考译文，交了之后可在页顶翻开对照" : "没有参考译文";

    public void Refresh()
    {
        var keep = Selected?.Id;
        _shelf = _book.Shelf();
        _syncing = true;
        try
        {
            Lines.Clear();
            foreach (var s in _shelf)
            {
                Lines.Add(new PaperLine(s.Paper.Id, s.Paper.Label, Mark("作", s.Paper.HasEssay, s.Essay),
                    Mark("译", s.Paper.HasTranslation, s.Translation), !s.Paper.Reviewed));
            }
        }
        finally
        {
            _syncing = false;
        }
        Selected = Lines.FirstOrDefault(l => l.Id == keep) ?? Lines.FirstOrDefault();
        Current = _shelf.FirstOrDefault(s => s.Paper.Id == Selected?.Id);
        var essays = _shelf.Count(s => s.Essay.HandedIn > 0);
        var translations = _shelf.Count(s => s.Translation.HandedIn > 0);
        Summary = _shelf.Count == 0 ? "还没有导入" : $"共 {_shelf.Count} 套 · 作文交过 {essays} 套 · 翻译交过 {translations} 套";
        OnPropertyChanged(nameof(IsEmpty));
    }

    /// <summary>Reads a content pack file and stores its papers; the report says what was left out and why.</summary>
    public void Import(string path)
    {
        try
        {
            var result = _book.ImportPapers(ExamPackFile.Read(path));
            var report = $"导入好了：新增 {result.Added} 套，更新 {result.Updated} 套，没变 {result.Unchanged} 套";
            if (result.Skipped.Count > 0)
            {
                report += $"；跳过 {result.Skipped.Count} 套："
                    + string.Join("；", result.Skipped.Take(3).Select(s => $"{s.Id} {s.Reason}"))
                    + (result.Skipped.Count > 3 ? "……" : "");
            }
            _say(report);
            Refresh();
        }
        catch (Exception e) when (e is NotebookException or IOException or UnauthorizedAccessException)
        {
            _say(e is NotebookException ? e.Message : "内容包文件读不了，没有导入");
        }
    }

    partial void OnSelectedChanged(PaperLine? value)
    {
        if (_syncing) return;
        Current = _shelf.FirstOrDefault(s => s.Paper.Id == value?.Id);
    }

    [RelayCommand(CanExecute = nameof(HasEssay))]
    private void StartEssay() => Start(PieceKind.Essay);

    [RelayCommand(CanExecute = nameof(HasTranslation))]
    private void StartTranslation() => Start(PieceKind.Translation);

    private void Start(PieceKind kind)
    {
        if (Current is null) return;
        try
        {
            _open(_book.StartFromPaper(Current.Paper.Id, kind));
        }
        catch (NotebookException e)
        {
            _say(e.Message);
        }
    }

    private static string Mark(string part, bool has, PartProgress progress) =>
        !has || progress.Pages == 0 ? "" : progress.HandedIn > 0 ? $"{part} ✓" : $"{part} ✎";

    private static string State(PartProgress progress) => progress switch
    {
        { Pages: 0 } => "还没写",
        { LatestIsDraft: true, HandedIn: 0 } => $"草稿在 No.{progress.LatestNumber:00}，还没交",
        { LatestIsDraft: true } => $"交过 {progress.HandedIn} 稿，No.{progress.LatestNumber:00} 在写",
        _ => $"交过 {progress.HandedIn} 稿",
    };

    private static string Action(PartProgress progress, string first) => progress switch
    {
        { Pages: 0 } => first,
        { LatestIsDraft: true } => "接着写",
        _ => "再做一遍",
    };

    private static string License(string license) => license switch
    {
        "personal-use" => "仅供个人学习",
        "redistributable" => "可再分发",
        _ => "原创练习",
    };
}
