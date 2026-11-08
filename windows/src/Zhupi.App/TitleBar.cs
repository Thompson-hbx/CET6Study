using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Zhupi.App;

// Paints the system title bar in the notebook's kraft colours instead of drawing our own:
// the native bar keeps snap layouts, double-click maximise and the system menu for free.
// Windows 10 ignores these attributes and keeps its default bar, which is fine.
internal static class TitleBar
{
    private const int UseImmersiveDarkMode = 20;
    private const int BorderColor = 34;
    private const int CaptionColor = 35;
    private const int TextColor = 36;

    public static void Tint(Window window, Color caption, Color text, Color border)
    {
        var handle = new WindowInteropHelper(window).EnsureHandle();
        // Light mode keeps the caption buttons dark, which reads on the light kraft colour.
        var light = 0;
        DwmSetWindowAttribute(handle, UseImmersiveDarkMode, ref light, sizeof(int));
        var colour = ColorRef(caption);
        DwmSetWindowAttribute(handle, CaptionColor, ref colour, sizeof(int));
        colour = ColorRef(text);
        DwmSetWindowAttribute(handle, TextColor, ref colour, sizeof(int));
        colour = ColorRef(border);
        DwmSetWindowAttribute(handle, BorderColor, ref colour, sizeof(int));
    }

    // COLORREF is 0x00BBGGRR.
    private static int ColorRef(Color c) => c.R | c.G << 8 | c.B << 16;

    // DllImport rather than LibraryImport: the source-generated version needs unsafe code for the ref argument.
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
