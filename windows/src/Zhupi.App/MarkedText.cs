using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Zhupi.App.ViewModels;
using Zhupi.Core.Teacher;

namespace Zhupi.App;

/// <summary>
/// Draws a handed-in page as a teacher would mark it: red underline for mistakes, dashed amber for
/// optional polish, a circled number after each mark, and taken fixes written in above the struck-out words.
/// </summary>
public static class MarkedText
{
    public static readonly DependencyProperty PiecesProperty = DependencyProperty.RegisterAttached(
        "Pieces", typeof(IReadOnlyList<TextPiece>), typeof(MarkedText), new PropertyMetadata(null, Rebuild));

    public static IReadOnlyList<TextPiece>? GetPieces(DependencyObject target) => (IReadOnlyList<TextPiece>?)target.GetValue(PiecesProperty);

    public static void SetPieces(DependencyObject target, IReadOnlyList<TextPiece>? value) => target.SetValue(PiecesProperty, value);

    private static readonly Brush Red = Frozen(Color.FromRgb(0xC2, 0x36, 0x2B));
    private static readonly Brush Amber = Frozen(Color.FromRgb(0xB0, 0x60, 0x2A));
    private static readonly Brush Grey = Frozen(Color.FromRgb(0x9A, 0x8F, 0x7C));
    private static readonly FontFamily Hand = new("Segoe Print, KaiTi");

    private static void Rebuild(DependencyObject target, DependencyPropertyChangedEventArgs e)
    {
        if (target is not TextBlock block) return;
        block.Inlines.Clear();
        foreach (var piece in (IReadOnlyList<TextPiece>?)e.NewValue ?? [])
        {
            if (piece.Mark is not { } mark)
            {
                block.Inlines.Add(new Run(piece.Text));
                continue;
            }
            var must = mark.Mark.Severity == Severity.Must;
            var pen = must ? Red : Amber;
            var words = new Run(piece.Text);
            switch (mark.Decision)
            {
                case Decision.Taken:
                    words.TextDecorations = Decoration(TextDecorationLocation.Strikethrough, Red, 1.6, dashed: false);
                    words.Foreground = Grey;
                    block.Inlines.Add(words);
                    block.Inlines.Add(new Run(" " + mark.Mark.Fix) { Foreground = Red, FontFamily = Hand, FontSize = block.FontSize * 0.95 });
                    break;
                case Decision.Left:
                    words.TextDecorations = Decoration(TextDecorationLocation.Underline, Grey, 1, dashed: true);
                    block.Inlines.Add(words);
                    break;
                default:
                    words.TextDecorations = Decoration(TextDecorationLocation.Underline, pen, must ? 2 : 1.6, dashed: !must);
                    block.Inlines.Add(words);
                    break;
            }
            block.Inlines.Add(new Run(MarkCard.Circle(mark.Mark.Number))
            {
                Foreground = mark.Decision == Decision.Left ? Grey : pen,
                FontSize = block.FontSize * 0.6,
                BaselineAlignment = BaselineAlignment.Superscript,
            });
        }
    }

    private static TextDecorationCollection Decoration(TextDecorationLocation where, Brush brush, double thickness, bool dashed)
    {
        var pen = new Pen(brush, thickness);
        if (dashed) pen.DashStyle = DashStyles.Dash;
        pen.Freeze();
        var decoration = new TextDecoration(where, pen, where == TextDecorationLocation.Underline ? 3 : 0,
            TextDecorationUnit.FontRecommended, TextDecorationUnit.Pixel);
        var collection = new TextDecorationCollection { decoration };
        collection.Freeze();
        return collection;
    }

    private static SolidColorBrush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }
}
