using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Inkwell.Core;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.ViewModels;

/// <summary>
/// The reference answer of the paper a page was started from. It stays folded away until the page is handed
/// in, so it cannot be copied from while drafting.
/// </summary>
public sealed partial class PageViewModel
{
    private ExamPaper? _paper;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ReferenceToggle))]
    public partial bool ShowingReference { get; set; }

    public string Reference => _paper is null ? "" : IsEssay ? _paper.ReferenceEssay : _paper.ReferenceTranslation;

    public bool HasReference => IsHandedIn && Reference.Length > 0;

    public string ReferenceHeading => IsEssay ? "参考范文" : "参考译文";

    public string ReferenceToggle => (ShowingReference ? "收起" : "翻开") + ReferenceHeading;

    public string ReferenceSource
    {
        get
        {
            if (_paper is null) return "";
            var source = IsEssay ? _paper.ReferenceEssaySource : _paper.ReferenceTranslationSource;
            return $"{_paper.Label} · " + (source.Length > 0 ? $"来源：{source}" : "来源未注明");
        }
    }

    [RelayCommand]
    private void ToggleReference() => ShowingReference = !ShowingReference;

    private void LoadReference(Piece page)
    {
        try
        {
            _paper = _book.PaperOf(page.Id);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            _paper = null;
            _say(DbError(e));
        }
        ShowingReference = false;
        NotifyReference();
    }

    public bool RefreshPaper(Piece? updated = null)
    {
        try
        {
            _paper = _book.PaperOf(_page.Id);
        }
        catch (Exception e) when (e is NotebookException or SqliteException)
        {
            _say(DbError(e));
            return false;
        }
        NotifyReference();
        if (!HasReference) ShowingReference = false;

        if (!_page.IsHandedIn && string.IsNullOrWhiteSpace(Text))
        {
            var paperTask = _paper is not null && Task == _page.Task ? _paper.Task(_page.Kind) : null;
            var targetTask = paperTask is { Length: > 0 }
                ? paperTask
                : updated is not null && updated.Id == _page.Id ? updated.Task : null;
            if (targetTask is { Length: > 0 } && targetTask != _page.Task)
            {
                if (updated is not null && updated.Id == _page.Id && updated.Task == targetTask)
                {
                    _page = updated;
                }
                else
                {
                    try
                    {
                        _page = _book.Write(_page.Id, _page.Title, targetTask, _page.Text);
                    }
                    catch (Exception e) when (e is NotebookException or SqliteException)
                    {
                        _say(DbError(e));
                        return false;
                    }
                }
                var wasEmptyTask = string.IsNullOrWhiteSpace(Task);
                _loading = true;
                Task = _page.Task;
                _loading = false;
                OnPropertyChanged(nameof(Header));
                OnPropertyChanged(nameof(Target));
                OnPropertyChanged(nameof(TargetStartX));
                OnPropertyChanged(nameof(WordProgress));
                OnPropertyChanged(nameof(WordNote));
                RefreshChecks();
                if (IsTranslationDraft && wasEmptyTask) IsEditingSource = false;
                RefreshSentences();
                Saved?.Invoke();
            }
        }
        return true;
    }

    private void NotifyReference()
    {
        OnPropertyChanged(nameof(Reference));
        OnPropertyChanged(nameof(HasReference));
        OnPropertyChanged(nameof(ReferenceHeading));
        OnPropertyChanged(nameof(ReferenceToggle));
        OnPropertyChanged(nameof(ReferenceSource));
    }
}
