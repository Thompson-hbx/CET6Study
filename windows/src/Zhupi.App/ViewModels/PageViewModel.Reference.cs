using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

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
            return $"{_paper.Label} · " + (source.Length > 0 ? $"来源：{source}" : "来源未注明") + " · 不是官方评分标准";
        }
    }

    [RelayCommand]
    private void ToggleReference() => ShowingReference = !ShowingReference;

    private void LoadReference(Piece page)
    {
        _paper = _book.PaperOf(page.Id);
        ShowingReference = false;
        OnPropertyChanged(nameof(Reference));
        OnPropertyChanged(nameof(HasReference));
        OnPropertyChanged(nameof(ReferenceHeading));
        OnPropertyChanged(nameof(ReferenceToggle));
        OnPropertyChanged(nameof(ReferenceSource));
    }
}
