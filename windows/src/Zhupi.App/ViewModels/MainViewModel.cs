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
        DataFile = dataFile;
        Message = "";
        CountText = "";
        Search = "";
        Tab = Book.Essays;
        OpenLatest();
    }

    public string DataFile { get; }

    public TeacherViewModel Teacher { get; }

    public ObservableCollection<TocLine> Contents { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsWritingBook), nameof(IsSettings), nameof(IsEmptyBook), nameof(BookName), nameof(Kind))]
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

    public bool IsWritingBook => Tab is Book.Essays or Book.Translations;

    public bool IsSettings => Tab == Book.Settings;

    public bool IsEmptyBook => Tab is Book.Words or Book.Review;

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

    partial void OnTabChanged(Book value)
    {
        Page?.Save();
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
        var latest = _notebook.Contents(Kind).FirstOrDefault();
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
            foreach (var p in _notebook.Contents(Kind, Search))
            {
                var title = p.Title.Trim().Length > 0 ? p.Title.Trim() : FirstLine(p.Task) ?? "（空白页）";
                var status = p.IsHandedIn ? "已交" : p.Revises is null ? "草稿" : "重写稿";
                Contents.Add(new TocLine(p.Id, p.Number.ToString("00"), title, status));
            }
            Selected = Contents.FirstOrDefault(line => line.Id == Page?.Id);
            CountText = $"共 {_notebook.Count(Kind)} 页";
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
