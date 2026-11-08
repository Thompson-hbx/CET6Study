using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;

namespace Zhupi.App;

// The title bar is drawn by MainWindow on the desk (WindowChrome). What the native bar gave for free is put back here:
// Windows 11 only offers snap layouts when the cursor is over a real maximize button, so hit-testing reports our
// button as HTMAXBUTTON and the clicks on it arrive as non-client messages that are handled here instead of by WPF.
// Windows 10 ignores the border colour and has no snap layouts; the button then simply maximizes.
internal static class TitleBar
{
    private const int BorderColor = 34;

    private const int WmNcHitTest = 0x0084;
    private const int WmNcMouseMove = 0x00A0;
    private const int WmNcLButtonDown = 0x00A1;
    private const int WmNcLButtonUp = 0x00A2;
    private const int WmNcMouseLeave = 0x02A2;
    private const int HtMaxButton = 9;

    private const int SmCxSizeFrame = 32;
    private const int SmCxPaddedBorder = 92;

    private const string Hot = "hot";
    private const string Pressed = "pressed";

    public static void Tint(Window window, Color border)
    {
        var handle = new WindowInteropHelper(window).EnsureHandle();
        var colour = border.R | border.G << 8 | border.B << 16;
        DwmSetWindowAttribute(handle, BorderColor, ref colour, sizeof(int));
    }

    public static void OfferSnapLayouts(Window window, Button maximize)
    {
        var handle = new WindowInteropHelper(window).EnsureHandle();
        HwndSource.FromHwnd(handle).AddHook((IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) =>
        {
            switch (msg)
            {
                case WmNcHitTest when Over(maximize, lParam):
                    handled = true;
                    return HtMaxButton;
                case WmNcMouseMove:
                    if (wParam == HtMaxButton)
                    {
                        if (maximize.Tag is not Pressed) maximize.Tag = Hot;
                        // Without this Windows never sends WM_NCMOUSELEAVE and the hover would stick.
                        var track = new TrackMouseEventData { Size = Marshal.SizeOf<TrackMouseEventData>(), Flags = 0x2 | 0x10, Window = hwnd };
                        TrackMouseEvent(ref track);
                    }
                    else
                    {
                        maximize.Tag = null;
                    }
                    break;
                case WmNcMouseLeave:
                    maximize.Tag = null;
                    break;
                case WmNcLButtonDown when wParam == HtMaxButton:
                    maximize.Tag = Pressed;
                    handled = true;
                    break;
                case WmNcLButtonUp when wParam == HtMaxButton:
                    if (maximize.Tag is Pressed) ToggleMaximize(window);
                    maximize.Tag = Hot;
                    handled = true;
                    break;
            }
            return IntPtr.Zero;
        });
    }

    public static void ToggleMaximize(Window window)
    {
        if (window.WindowState == WindowState.Maximized) SystemCommands.RestoreWindow(window);
        else SystemCommands.MaximizeWindow(window);
    }

    /// <summary>
    /// A maximized WindowChrome window still has its resize frame, which hangs off every screen edge;
    /// padding by that much keeps the title bar and the notebook on screen.
    /// </summary>
    public static Thickness MaximizedOverhang(Window window)
    {
        var scale = VisualTreeHelper.GetDpi(window);
        var dpi = (uint)Math.Round(96 * scale.DpiScaleX);
        var pixels = GetSystemMetricsForDpi(SmCxSizeFrame, dpi) + GetSystemMetricsForDpi(SmCxPaddedBorder, dpi);
        return new Thickness(pixels / scale.DpiScaleX);
    }

    private static bool Over(FrameworkElement element, IntPtr lParam)
    {
        if (!element.IsVisible || PresentationSource.FromVisual(element) is null) return false;
        // Screen coordinates are signed 16-bit halves; monitors left of or above the primary one are negative.
        var value = lParam.ToInt64();
        var point = element.PointFromScreen(new Point((short)(value & 0xFFFF), (short)((value >> 16) & 0xFFFF)));
        return point.X >= 0 && point.Y >= 0 && point.X < element.ActualWidth && point.Y < element.ActualHeight;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TrackMouseEventData
    {
        public int Size;
        public int Flags;
        public IntPtr Window;
        public int HoverTime;
    }

    // DllImport rather than LibraryImport: the source-generated version needs unsafe code for the ref argument.
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    [DllImport("user32.dll")]
    private static extern bool TrackMouseEvent(ref TrackMouseEventData data);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetricsForDpi(int index, uint dpi);
}
