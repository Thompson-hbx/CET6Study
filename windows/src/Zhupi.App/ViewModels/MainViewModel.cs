using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

/// <summary>Divider tabs down the left edge of the notebook.</summary>
public enum Book
{
    Essays,
    Translations,
    Papers,
    Words,
    Review,
    Settings,
}

public sealed record TocLine(string Id, string Number, string Title, string Status);

public sealed partial class MainViewModel : ObservableObject
{
    private readonly Notebook _notebook;
    private readonly TeacherSettings _teacher;
    private bool _syncing;

    public MainViewModel(Notebook notebook, string dataFile, TeacherSettings teacher)
    {
        _notebook = notebook;
        _teacher = teacher;
        Teacher = new TeacherViewModel(teacher);
        Words = new WordsViewModel(notebook, TimeProvider.System, text => Message = text);
        LookBack = new LookBackViewModel(notebook);
        Papers = new PapersViewModel(notebook, text => Message = text, OpenFromPaper);
        DataFile = dataFile;
        Message = "";
        CountText = "";
        Search = "";
        Tab = Book.Essays;
        OpenLatest();
    }

    public string DataFile { get; }

    public TeacherViewModel Teacher { get; }

    public WordsViewModel Words { get; }

    public LookBackViewModel LookBack { get; }

    public PapersViewModel Papers { get; }

    public ObservableCollection<TocLine> Contents { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsWritingBook), nameof(IsSettings), nameof(IsPapers), nameof(IsWords), nameof(IsLookBack), nameof(BookName), nameof(Kind))]
    public partial Book Tab { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasPage))]
    public partial PageViewModel? Page { get; set; }

    [ObservableProperty]
    public partial TocLine? Selected { get; set; }

    [ObservableProperty]
    public partial string Search { get; set; }

    [ObservableProperty]
    public partial string Message { get; set; }

    [ObservableProperty]
    public partial string CountText { get; set; }

    /// <summary>The contents list shows the book's trash instead of its pages.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TocHeading), nameof(TrashLabel), nameof(HasTrash))]
    public partial bool ShowingTrash { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrashLabel), nameof(HasTrash))]
    public partial int TrashCount { get; set; }

    /// <summary>An empty trash has nothing to open, so its entry only appears once a page is in it.</summary>
    public bool HasTrash => ShowingTrash || TrashCount > 0;

    public string TocHeading => ShowingTrash ? "废 纸 篓" : "目  录";

    public string TrashLabel => ShowingTrash ? "← 回到目录" : $"废纸篓 · {TrashCount}";

    /// <summary>Asks the learner before something cannot be undone; the window supplies the dialog.</summary>
    public Func<string, bool> Confirm { get; set; } = _ => false;

    public bool IsWritingBook => Tab is Book.Essays or Book.Translations;

    public bool IsSettings => Tab == Book.Settings;

    public bool IsPapers => Tab == Book.Papers;

    public bool IsWords => Tab == Book.Words;

    public bool IsLookBack => Tab == Book.Review;

    public bool HasPage => Page is not null;

    public string BookName => Tab == Book.Translations ? "翻译本" : "作文本";

    public PieceKind Kind => Tab == Book.Translations ? PieceKind.Translation : PieceKind.Essay;

    public bool CanImport => _notebook.IsEmpty;

    [RelayCommand]
    private void ShowTab(Book tab) => Tab = tab;

    [RelayCommand]
    private void NewPage()
    {
        if (!IsWritingBook) Tab = Book.Essays;
        ShowingTrash = false;
        Page?.Save();
        Show(_notebook.NewPage(Kind));
        Message = "翻开了新的一页";
    }

    /// <summary>Moves essay and translation texts from the old phone backup into this empty notebook.</summary>
    public void ImportOld(string path)
    {
        try
        {
            var summary = OldNotesImport.Run(path, _notebook);
            Message = $"搬好了：作文 {summary.Essays} 篇、翻译 {summary.Translations} 段（其中已交 {summary.HandedIn}），其他 {summary.SkippedOther} 条没有搬";
            OnPropertyChanged(nameof(CanImport));
            Tab = Book.Essays;
            OpenLatest();
        }
        catch (Exception e) when (e is NotebookException or Microsoft.Data.Sqlite.SqliteException or IOException)
        {
            Message = e is NotebookException ? e.Message : "旧数据文件读不了，没有搬";
        }
    }

    public void SaveOpenPage() => Page?.Save();

    [RelayCommand]
    private void ToggleTrash()
    {
        Page?.Save();
        ShowingTrash = !ShowingTrash;
        Search = "";
        OpenFirst();
    }

    [RelayCommand]
    private void Discard(TocLine? line)
    {
        if (line is null || ShowingTrash) return;
        if (line.Id == Page?.Id) Page?.Save();
        _notebook.Discard(line.Id);
        Message = $"「{line.Title}」放进了废纸篓";
        if (line.Id == Page?.Id) OpenFirst();
        else Refresh();
    }

    [RelayCommand]
    private void Restore(TocLine? line)
    {
        if (line is null || !ShowingTrash) return;
        _notebook.Restore(line.Id);
        Message = $"「{line.Title}」放回了目录";
        LeaveTrashIfEmpty(line.Id);
    }

    [RelayCommand]
    private void Purge(TocLine? line)
    {
        if (line is null || !ShowingTrash) return;
        if (!Confirm($"彻底删除「{line.Title}」？它的批改、问答和翻译提示会一起删掉，不能恢复。")) return;
        _notebook.Purge(line.Id);
        Message = $"「{line.Title}」已彻底删除";
        LeaveTrashIfEmpty(line.Id);
    }

    /// <summary>Once the last page has left the trash there is nothing to look at there, so go back to the contents.</summary>
    private void LeaveTrashIfEmpty(string changedId)
    {
        if (_notebook.Trash(Kind).Count == 0)
        {
            ShowingTrash = false;
            OpenFirst();
        }
        else if (changedId == Page?.Id)
        {
            OpenFirst();
        }
        else
        {
            Refresh();
        }
    }

    /// <summary>The Delete key: into the trash from the contents, out of the notebook from the trash.</summary>
    [RelayCommand]
    private void DeleteSelected()
    {
        if (ShowingTrash) Purge(Selected);
        else Discard(Selected);
    }

    /// <summary>Turns to the essay or translation book at the page started from a paper.</summary>
    private void OpenFromPaper(Piece piece)
    {
        Tab = piece.Kind == PieceKind.Essay ? Book.Essays : Book.Translations;
        Search = "";
        if (piece.Id != Page?.Id)
        {
            Page?.Save();
            Show(piece);
        }
        Message = $"翻到了「{piece.Title}」";
    }

    partial void OnTabChanged(Book value)
    {
        Page?.Save();
        ShowingTrash = false;
        if (Tab == Book.Words) Words.Refresh();
        if (Tab == Book.Review) LookBack.Refresh();
        if (Tab == Book.Papers) Papers.Refresh();
        if (!IsWritingBook) return;
        Search = "";
        if (Page is null || Page.IsEssay != (Kind == PieceKind.Essay)) OpenLatest();
        else Refresh();
    }

    partial void OnSearchChanged(string value) => Refresh();

    partial void OnSelectedChanged(TocLine? value)
    {
        if (_syncing || value is null || value.Id == Page?.Id) return;
        Page?.Save();
        Show(_notebook.Get(value.Id));
    }

    private void OpenLatest()
    {
        ShowingTrash = false;
        OpenFirst();
    }

    /// <summary>Opens the top line of whichever list is showing, the contents or the trash.</summary>
    private void OpenFirst()
    {
        var latest = (ShowingTrash ? _notebook.Trash(Kind) : _notebook.Contents(Kind)).FirstOrDefault();
        if (latest is null)
        {
            Page = null;
            Refresh();
        }
        else
        {
            Show(latest);
        }
    }

    private void Show(Piece piece)
    {
        var page = new PageViewModel(_notebook, piece, _teacher, text => Message = text);
        page.Saved += Refresh;
        page.Turned += Show;
        Page = page;
        Refresh();
    }

    private void Refresh()
    {
        _syncing = true;
        try
        {
            Contents.Clear();
            foreach (var p in ShowingTrash ? _notebook.Trash(Kind) : _notebook.Contents(Kind, Search))
            {
                var title = p.Title.Trim().Length > 0 ? p.Title.Trim() : FirstLine(p.Task) ?? "（空白页）";
                var status = p.IsHandedIn ? "已交" : p.Revises is null ? "草稿" : "重写稿";
                Contents.Add(new TocLine(p.Id, p.Number.ToString("00"), title, status));
            }
            Selected = Contents.FirstOrDefault(line => line.Id == Page?.Id);
            CountText = $"共 {_notebook.Count(Kind)} 页";
            TrashCount = _notebook.Trash(Kind).Count;
        }
        finally
        {
            _syncing = false;
        }
    }

    private static string? FirstLine(string text)
    {
        var line = text.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0);
        return line is null ? null : line.Length > 24 ? line[..24] + "…" : line;
    }
}
