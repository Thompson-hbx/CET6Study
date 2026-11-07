using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

/// <summary>A run of the handed-in text; <see cref="Mark"/> is set when the teacher marked it.</summary>
public sealed record TextPiece(string Text, PlacedMark? Mark);

/// <summary>One red-pen note in the margin, with the learner's take / leave choice.</summary>
public sealed partial class MarkCard : ObservableObject
{
    private readonly Action<int, Decision> _decide;
    private readonly Action<int> _ask;
    private readonly Action<int> _collect;

    public MarkCard(PlacedMark placed, Action<int, Decision> decide, Action<int> ask, Action<int> collect)
    {
        Placed = placed;
        _decide = decide;
        _ask = ask;
        _collect = collect;
        Decision = placed.Decision;
    }

    [RelayCommand]
    private void Ask() => _ask(Number);

    [RelayCommand]
    private void Collect() => _collect(Number);

    public PlacedMark Placed { get; }

    public int Number => Placed.Mark.Number;

    public string Circled => Circle(Number);

    public bool IsMust => Placed.Mark.Severity == Severity.Must;

    public string Label => (IsMust ? "必改" : "可改") + (Placed.Mark.Kind.Length > 0 ? " · " + Placed.Mark.Kind : "");

    public string Quote => Placed.Mark.Quote;

    public string Fix => Placed.Mark.Fix;

    public string Why => Placed.Mark.Why;

    public bool IsPlaced => Placed.IsPlaced;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsTaken), nameof(IsLeft))]
    public partial Decision Decision { get; set; }

    public bool IsTaken => Decision == Decision.Taken;

    public bool IsLeft => Decision == Decision.Left;

    [RelayCommand]
    private void Take() => Choose(Decision == Decision.Taken ? Decision.Open : Decision.Taken);

    [RelayCommand]
    private void Leave() => Choose(Decision == Decision.Left ? Decision.Open : Decision.Left);

    private void Choose(Decision decision)
    {
        _decide(Number, decision);
        Decision = decision;
    }

    public static string Circle(int n) => n is >= 1 and <= 20 ? ((char)('①' + n - 1)).ToString() : $"({n})";
}
