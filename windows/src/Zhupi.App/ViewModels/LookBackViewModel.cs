using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Zhupi.Core;

namespace Zhupi.App.ViewModels;

/// <summary>One bar in the band chart; essays in red ink, translations in blue.</summary>
public sealed record BandBar(double Height, int Band, string Label, string Tip, bool IsEssay);

public sealed record HabitLine(string Kind, string Count, string Example);

/// <summary>The 复盘 spread: how much was written, how the bands moved, which mistakes keep coming back.</summary>
public sealed partial class LookBackViewModel(Notebook book) : ObservableObject
{
    private const double BarPerBand = 12;

    public ObservableCollection<BandBar> Bars { get; } = [];

    public ObservableCollection<HabitLine> Habits { get; } = [];

    [ObservableProperty]
    public partial LookBack? Data { get; set; }

    [ObservableProperty]
    public partial string Decisions { get; set; } = "";

    [ObservableProperty]
    public partial string WordsLine { get; set; } = "";

    [ObservableProperty]
    public partial bool HasBands { get; set; }

    [ObservableProperty]
    public partial bool HasHabits { get; set; }

    public void Refresh()
    {
        var data = book.LookBack();
        Bars.Clear();
        foreach (var b in data.Bands)
        {
            var label = (b.Kind == PieceKind.Essay ? "作" : "译") + b.Number.ToString("00");
            Bars.Add(new BandBar(b.Band * BarPerBand, b.Band, label, $"{label} {b.Title} · {b.Band} 档", b.Kind == PieceKind.Essay));
        }
        Habits.Clear();
        foreach (var h in data.Habits)
        {
            Habits.Add(new HabitLine(h.Kind, $"{h.Count} 次", h.Quote.Length > 0 ? $"「{h.Quote}」→「{h.Fix}」" : ""));
        }
        var decided = data.Taken + data.Left + data.Undecided;
        Decisions = decided == 0 ? "还没有批注。" : $"采纳 {data.Taken} 处，决定不改 {data.Left} 处，还没定 {data.Undecided} 处。";
        WordsLine = data.Words == 0
            ? "单词本还是空的。"
            : $"共 {data.Words} 条，今天待复习 {data.WordsDue} 条；这周复习了 {data.WordReviewsThisWeek} 次，其中记得 {data.RememberedThisWeek} 次。";
        HasBands = Bars.Count > 0;
        HasHabits = Habits.Count > 0;
        Data = data;
    }
}
