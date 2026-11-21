using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Inkwell.Core;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.ViewModels;

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
    private readonly Dictionary<string, PageViewModel> _waiting = [];
    private bool _syncing;
    private bool _openingFromPaper;
    private bool _cleanupImportTemp;
    private HashSet<string>? _preExistingImportDirs;

    public MainViewModel(Notebook notebook, string dataFile, TeacherSettings teacher)
    {
        _notebook = notebook;
        _teacher = teacher;
        Teacher = new TeacherViewModel(teacher);
        Words = new WordsViewModel(notebook, TimeProvider.System, text => Message = text);
        Words.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(WordsViewModel.Notice)) OnPropertyChanged(nameof(WordsToast));
        };
        LookBack = new LookBackViewModel(notebook, text => Message = text);
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
    [NotifyPropertyChangedFor(nameof(IsWritingBook), nameof(IsSettings), nameof(IsPapers), nameof(IsWords), nameof(IsLookBack), nameof(BookName), nameof(Kind), nameof(VisiblePage), nameof(WordsToast))]
    public partial Book Tab { get; private set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasPage), nameof(VisiblePage))]
    public partial PageViewModel? Page { get; set; }

    [ObservableProperty]
    public partial TocLine? Selected { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasSearchText))]
    [NotifyCanExecuteChangedFor(nameof(ClearSearchCommand))]
    public partial string Search { get; set; }

    [ObservableProperty]
    public partial string SearchSummary { get; private set; } = "";

    public bool HasSearchText => Search.Length > 0;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(WordsToast))]
    public partial string Message { get; set; }

    public string WordsToast => IsWords && Message.Length > 0 && Message != Words.Notice ? Message : "";

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

    public PageViewModel? VisiblePage => IsWritingBook ? Page : null;

    public string BookName => Tab == Book.Translations ? "翻译本" : "作文本";

    public PieceKind Kind => Tab == Book.Translations ? PieceKind.Translation : PieceKind.Essay;

    private CancellationTokenSource? _oldImport;

    public bool CanImport
    {
        get
        {
            if (IsImportingOld) return false;
            try
            {
                return _notebook.IsEmpty;
            }
            catch (Exception e) when (e is NotebookException or SqliteException)
            {
                return false;
            }
        }
    }

    public bool NotImportingOld => !IsImportingOld;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanImport), nameof(NotImportingOld))]
    [NotifyCanExecuteChangedFor(nameof(ShowTabCommand), nameof(NewPageCommand))]
    public partial bool IsImportingOld { get; private set; }

    /// <summary>The outcome of moving the old notebook, kept apart from <see cref="Message"/> so the import card shows only its own news.</summary>
    [ObservableProperty]
    public partial string ImportNote { get; set; } = "";

    private bool CanShowTab(Book tab) => !IsImportingOld;

    [RelayCommand(CanExecute = nameof(CanShowTab))]
    private void ShowTab(Book tab)
    {
        if (IsImportingOld || tab == Tab) return;
        if (SaveOpenPage()) Tab = tab;
        else OnPropertyChanged(nameof(Tab));
    }

    [RelayCommand(CanExecute = nameof(NotImportingOld))]
    private void NewPage()
    {
        if (IsImportingOld || !SaveOpenPage()) return;
        if (!IsWritingBook) Tab = Book.Essays;
        try
        {
            TurnTo(_notebook.NewPage(Kind));
            Message = "翻开了新的一页";
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
        }
    }

    /// <summary>Moves essay and translation texts from the old phone backup into this empty notebook.</summary>
    public async Task ImportOldAsync(string path)
    {
        if (!CanImport) return;
        using var cancel = new CancellationTokenSource();
        _oldImport = cancel;
        IsImportingOld = true;
        ImportNote = "正在读取旧数据文件…";
        try
        {
            _preExistingImportDirs = SnapshotImportTempDirs(Path.GetTempPath());
            _cleanupImportTemp = true;
            var summary = await OldNotesImport.RunAsync(path, _notebook, cancel.Token);
            Message = ImportNote = $"搬好了：作文 {summary.Essays} 篇、翻译 {summary.Translations} 段（其中已交 {summary.HandedIn}），其他 {summary.SkippedOther} 条没有搬";
            Tab = Book.Essays;
            OpenLatest();
        }
        catch (OperationCanceledException)
        {
            Message = ImportNote = "已取消搬旧本子，没有改动。";
        }
        catch (Exception e) when (e is NotebookException or SqliteException or IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            Message = ImportNote = e is NotebookException ? e.Message : "旧数据文件读不了，没有搬";
        }
        finally
        {
            _oldImport = null;
            IsImportingOld = false;
            _cleanupImportTemp = false;
            _preExistingImportDirs = null;
        }
    }

    [RelayCommand]
    public void CancelOldImport() => _oldImport?.Cancel();

    public bool HasPendingWork => Page is { IsBusy: true } || _waiting.Count > 0 || IsImportingOld || Teacher.Busy;

    public IReadOnlyList<string> PendingWorkLines()
    {
        var lines = new List<string>();
        if (Page is { IsBusy: true } || _waiting.Count > 0) lines.Add("• 还有正在等老师回复的请求");
        if (Teacher.Busy) lines.Add(Teacher.WaitingForBrowser ? "• 正在等浏览器完成 ChatGPT 登录" : "• 正在处理老师设置操作");
        if (IsImportingOld) lines.Add("• 正在读取旧数据文件");
        return lines;
    }

    public void CancelPendingWork()
    {
        Page?.Abandon();
        foreach (var waiting in _waiting.Values.ToList()) waiting.Abandon();
        _waiting.Clear();
        Teacher.CancelPending();
        CancelOldImport();
    }

    public void CleanupImportTempDirs()
    {
        if (!_cleanupImportTemp) return;
        CleanupImportTempDirs(Path.GetTempPath(), _preExistingImportDirs);
    }

    internal static HashSet<string> SnapshotImportTempDirs(string tempRoot)
    {
        try
        {
            return Directory.Exists(tempRoot)
                ? new HashSet<string>(Directory.EnumerateDirectories(tempRoot, "inkwell-import-*"), StringComparer.OrdinalIgnoreCase)
                : new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }
    }

    internal static void CleanupImportTempDirs(string tempRoot, IReadOnlySet<string>? preExisting)
    {
        IEnumerable<string> candidates;
        try
        {
            if (!Directory.Exists(tempRoot)) return;
            candidates = Directory.EnumerateDirectories(tempRoot, "inkwell-import-*").ToList();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return;
        }

        foreach (var dir in candidates)
        {
            if (preExisting is not null && preExisting.Contains(dir)) continue;
            for (var attempt = 0; attempt < 10; attempt++)
            {
                try
                {
                    if (!Directory.Exists(dir)) break;
                    Directory.Delete(dir, recursive: true);
                    break;
                }
                catch (DirectoryNotFoundException)
                {
                    break;
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    if (attempt < 9) Thread.Sleep(50);
                }
            }
        }
    }

    public bool SaveOpenPage() => Page?.TrySave() ?? true;

    [RelayCommand]
    private void ToggleTrash()
    {
        if (!SaveOpenPage()) return;
        ShowingTrash = !ShowingTrash;
        Search = "";
        OpenFirst();
    }

    [RelayCommand]
    private void Discard(TocLine? line)
    {
        if (line is null || ShowingTrash) return;
        if (line.Id == Page?.Id && !SaveOpenPage()) return;
        try
        {
            _notebook.Discard(line.Id);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
            return;
        }
        Message = $"「{line.Title}」放进了废纸篓";
        if (line.Id == Page?.Id) OpenFirst();
        else Refresh();
    }

    [RelayCommand]
    private void Restore(TocLine? line)
    {
        if (line is null || !ShowingTrash) return;
        if (line.Id == Page?.Id && !SaveOpenPage()) return;
        try
        {
            _notebook.Restore(line.Id);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
            return;
        }
        Message = $"「{line.Title}」放回了目录";
        LeaveTrashIfEmpty(line.Id);
    }

    [RelayCommand]
    private void Purge(TocLine? line)
    {
        if (line is null || !ShowingTrash) return;
        if (!Confirm($"彻底删除「{line.Title}」？它的批改、问答和翻译提示会一起删掉，不能恢复。")) return;
        // Write the pending pause now, so no save is left to land on a page that no longer exists.
        if (line.Id == Page?.Id && !SaveOpenPage()) return;
        try
        {
            _notebook.Purge(line.Id);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
            return;
        }
        // Nothing a running request brings back could be stored any more.
        if (line.Id == Page?.Id) Page?.Abandon();
        if (_waiting.Remove(line.Id, out var waiting)) waiting.Abandon();
        Message = $"「{line.Title}」已彻底删除";
        LeaveTrashIfEmpty(line.Id);
    }

    /// <summary>Once the last page has left the trash there is nothing to look at there, so go back to the contents.</summary>
    private void LeaveTrashIfEmpty(string changedId)
    {
        int remaining;
        try
        {
            remaining = _notebook.CountTrash(Kind);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
            return;
        }
        if (remaining == 0)
        {
            ShowingTrash = false;
            Search = "";
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
        var targetTab = piece.Kind == PieceKind.Essay ? Book.Essays : Book.Translations;
        _openingFromPaper = true;
        try
        {
            Tab = targetTab;
        }
        finally
        {
            _openingFromPaper = false;
        }
        Search = "";
        bool refreshed;
        if (Page?.Id == piece.Id)
        {
            refreshed = Page.RefreshPaper(piece);
            Refresh();
        }
        else
        {
            refreshed = Show(piece);
        }
        if (refreshed) Message = $"翻到了「{piece.Title}」";
    }

    partial void OnTabChanged(Book oldValue, Book newValue)
    {
        if (ShowingTrash)
        {
            ShowingTrash = false;
            Leave(Page);
            Page = null;
        }
        if (Tab == Book.Words) Words.Refresh();
        if (Tab == Book.Review) LookBack.Refresh();
        if (Tab == Book.Papers) Papers.Refresh();
        if (Tab == Book.Settings) OnPropertyChanged(nameof(CanImport));
        if (!IsWritingBook || _openingFromPaper) return;
        Search = "";
        if (Page is null || Page.IsEssay != (Kind == PieceKind.Essay))
        {
            OpenLatest();
            Page?.RefreshPaper();
        }
        else
        {
            Refresh();
            Page.RefreshPaper();
        }
    }

    [RelayCommand(CanExecute = nameof(HasSearchText))]
    private void ClearSearch() => Search = "";

    partial void OnSearchChanged(string value) => Refresh();

    partial void OnSelectedChanged(TocLine? oldValue, TocLine? newValue)
    {
        if (_syncing || newValue is null || newValue.Id == Page?.Id) return;
        if (SaveOpenPage())
        {
            try
            {
                Show(_notebook.Get(newValue.Id));
                return;
            }
            catch (Exception e) when (e is NotebookException or SqliteException)
            {
                Message = PageViewModel.DbError(e);
            }
        }

        _syncing = true;
        Selected = oldValue;
        _syncing = false;
    }

    private void OpenLatest()
    {
        ShowingTrash = false;
        OpenFirst();
    }

    /// <summary>Opens the top line of whichever list is showing, the contents or the trash.</summary>
    private void OpenFirst()
    {
        Piece? latest;
        try
        {
            latest = (ShowingTrash ? _notebook.Trash(Kind) : _notebook.Contents(Kind)).FirstOrDefault();
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
            return;
        }
        if (latest is null)
        {
            Leave(Page);
            Page = null;
            Refresh();
        }
        else
        {
            Show(latest);
        }
    }

    private void TurnTo(Piece piece)
    {
        // New drafts belong in the contents and may not match the current search.
        if (piece.Id != Page?.Id)
        {
            ShowingTrash = false;
            Search = "";
        }
        Show(piece);
    }

    private bool Show(Piece piece)
    {
        // Reopening the page that is waiting for the teacher: keep that view, so its answer still shows up here
        // and the buttons stay disabled instead of offering a second paid request.
        if (Page is { IsBusy: true } current && current.Id == piece.Id)
        {
            var ok = current.RefreshPaper();
            Refresh();
            return ok;
        }
        bool refreshed;
        if (_waiting.Remove(piece.Id, out var page))
        {
            page.Reopen();
            refreshed = page.RefreshPaper();
        }
        else
        {
            page = new PageViewModel(_notebook, piece, _teacher, text => Message = text, text => Confirm(text));
            refreshed = page.RefreshPaper();
            page.Settled += () =>
            {
                if (Page != page) _waiting.Remove(page.Id);
            };
            page.NotebookUpdated += OnPageNotebookUpdated;
        }
        page.Saved += Refresh;
        page.Turned += TurnTo;
        Leave(Page);
        Page = page;
        Refresh();
        return refreshed;
    }

    private void OnPageNotebookUpdated()
    {
        if (Tab == Book.Review) LookBack.Refresh();
    }

    /// <summary>A page left while its request runs is kept until the answer is stored, see <see cref="PageViewModel.Close"/>.</summary>
    private void Leave(PageViewModel? page)
    {
        if (page is null) return;
        page.Close();
        if (page.IsBusy && !page.IsAbandoned) _waiting[page.Id] = page;
    }

    private void Refresh()
    {
        _syncing = true;
        try
        {
            var pages = ShowingTrash ? _notebook.Trash(Kind, Search) : _notebook.Contents(Kind, Search);
            Contents.Clear();
            foreach (var p in pages)
            {
                var title = p.Title.Trim().Length > 0 ? p.Title.Trim() : FirstLine(p.Task) ?? "（空白页）";
                var status = p.IsHandedIn ? "已交" : p.Revises is null ? "草稿" : "重写稿";
                Contents.Add(new TocLine(p.Id, p.Number.ToString("00"), title, status));
            }
            Selected = Contents.FirstOrDefault(line => line.Id == Page?.Id);
            TrashCount = _notebook.CountTrash(Kind);
            CountText = $"共 {(ShowingTrash ? TrashCount : _notebook.Count(Kind))} 页";
            SearchSummary = string.IsNullOrWhiteSpace(Search)
                ? ""
                : pages.Count == 0 ? "没有匹配的页" : $"找到 {pages.Count} 页";
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            Message = PageViewModel.DbError(e);
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
