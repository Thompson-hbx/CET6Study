using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Inkwell.App.ViewModels;
using Inkwell.Core;
using Inkwell.Core.Teacher;

namespace Inkwell.App.Tests;

[CollectionDefinition("WpfApp")]
public sealed class WpfAppCollection : ICollectionFixture<WpfAppFixture>;

public sealed class WpfAppFixture : IDisposable
{
    private readonly Thread _thread;
    private readonly ManualResetEventSlim _ready = new(false);
    private Dispatcher? _dispatcher;

    public WpfAppFixture()
    {
        _thread = new Thread(() =>
        {
            _dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(_dispatcher));
            if (Application.Current is null)
            {
                var app = new App();
                app.InitializeComponent();
                app.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            }
            else
            {
                Application.Current.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            }
            _ready.Set();
            Dispatcher.Run();
        })
        {
            IsBackground = true,
        };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
        _ready.Wait();
    }

    public Task RunAsync(Action action)
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _dispatcher!.BeginInvoke(() =>
        {
            try
            {
                action();
                tcs.SetResult();
            }
            catch (Exception ex)
            {
                tcs.SetException(ex);
            }
        });
        return tcs.Task;
    }

    public void Dispose()
    {
        _dispatcher?.BeginInvokeShutdown(DispatcherPriority.Normal);
        _thread.Join(TimeSpan.FromSeconds(5));
        _ready.Dispose();
    }
}

[Collection("WpfApp")]
public sealed class MainWindowStaTest(WpfAppFixture wpf) : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-wpf-sta-");

    public void Dispose() => _dir.Delete(recursive: true);

    [Fact]
    public Task OffScreenMainWindowHasZeroBindingErrorsAndCleanLayoutAcrossTabsAt1100And1440() => wpf.RunAsync(() =>
    {
        var dbPath = Path.Combine(_dir.FullName, "study.db");
        using var book = Notebook.Open(dbPath, TimeProvider.System);
        SeedNotebook(book);

        var bindingErrors = new List<string>();
        var listener = new RecordingTraceListener(bindingErrors);
        PresentationTraceSources.Refresh();
        PresentationTraceSources.DataBindingSource.Listeners.Add(listener);
        PresentationTraceSources.DataBindingSource.Switch.Level = SourceLevels.Warning;

        try
        {
            var settings = new TeacherSettings(_dir.FullName);
            var model = new MainViewModel(book, dbPath, settings);
            var window = CreateOffScreenWindow(model, 1100, 640);
            try
            {
                window.Show();
                Pump(window);

                // Cycle all 6 tabs at 1100x640 and render off-screen bitmap.
                foreach (Book tab in Enum.GetValues<Book>())
                {
                    model.ShowTabCommand.Execute(tab);
                    Pump(window);
                    RenderRootBitmap(window);
                }

                // Check Settings tab DividerTab expands when checked (not stuck at 48).
                model.ShowTabCommand.Execute(Book.Settings);
                Pump(window);
                var settingsTab = FindVisualChildren<RadioButton>(window)
                    .Single(r => Equals(r.Content, "设置"));
                Assert.True(settingsTab.IsChecked == true);
                Assert.True(settingsTab.ActualHeight > 48, $"Expected expanded Settings tab > 48, got {settingsTab.ActualHeight}");

                model.Teacher.Service = TeacherService.ChatGpt;
                Pump(window);
                var accountText = FindVisualChildren<TextBlock>(window)
                    .Single(t => t.IsVisible && t.Text == model.Teacher.Account);
                Assert.Equal(TextWrapping.Wrap, accountText.TextWrapping);
                model.Teacher.Service = TeacherService.DeepSeek;
                Pump(window);

                // Check KeyBox clears on Service switch and leaving Book.Settings.
                var keyBox = FindVisualChildren<PasswordBox>(window).Single();
                keyBox.Password = "secret-1";
                model.Teacher.Service = TeacherService.Compatible;
                Pump(window);
                Assert.Equal("", keyBox.Password);

                keyBox.Password = "secret-2";
                model.ShowTabCommand.Execute(Book.Essays);
                Pump(window);
                Assert.Equal("", keyBox.Password);

                // Check SearchBox Ctrl+Enter is handled locally and does not trigger HandInOrRewrite.
                var searchBox = FindVisualChildren<TextBox>(window).Single(t => t.Name == "SearchBox");
                Assert.Contains(searchBox.InputBindings.OfType<KeyBinding>(), kb => kb.Key == Key.Escape);
                var pageBefore = model.Page!;
                var wasHandedIn = pageBefore.IsHandedIn;
                var source = PresentationSource.FromVisual(searchBox)!;
                var ctrlEnter = new KeyEventArgs(new ControlKeyboardDevice(), source, 0, Key.Enter)
                {
                    RoutedEvent = UIElement.PreviewKeyDownEvent,
                };
                searchBox.RaiseEvent(ctrlEnter);
                Assert.True(ctrlEnter.Handled);
                Assert.Same(pageBefore, model.Page);
                Assert.Equal(wasHandedIn, model.Page!.IsHandedIn);

                // Check Review left page ScrollViewer & 12 bars fit at 1100x640 without clipping.
                model.ShowTabCommand.Execute(Book.Review);
                Pump(window);
                var chart = FindVisualChildren<ItemsControl>(window)
                    .First(ic => Equals(System.Windows.Automation.AutomationProperties.GetName(ic), "档位图"));
                Assert.Equal(12, model.LookBack.Bars.Count);
                var grid = FindVisualChildren<UniformGrid>(chart).Single();
                Assert.Equal(12, grid.Columns);
                Assert.True(grid.ActualWidth > 200);
                foreach (var child in grid.Children.OfType<FrameworkElement>())
                {
                    var right = child.TransformToAncestor(chart).Transform(new Point(child.ActualWidth, 0)).X;
                    Assert.True(right <= chart.ActualWidth + 1.0, $"Bar clipped: {right} > {chart.ActualWidth}");
                }

                // Check Settings LineCombo PART_Popup.Child renders off-screen cleanly.
                model.ShowTabCommand.Execute(Book.Settings);
                Pump(window);
                var combos = FindVisualChildren<ComboBox>(window).ToList();
                Assert.Equal(2, combos.Count);
                foreach (var combo in combos)
                {
                    combo.IsDropDownOpen = true;
                    Pump(window);
                    var popup = combo.Template.FindName("PART_Popup", combo) as Popup;
                    Assert.NotNull(popup);
                    Assert.NotNull(popup!.Child);
                    combo.IsDropDownOpen = false;
                    Pump(window);
                }

                // Check REV-H08: Translation draft right margin ScrollViewer at 1100x640.
                model.ShowTabCommand.Execute(Book.Translations);
                Pump(window);
                var transDraft = model.Page!;
                transDraft.SelectSentenceCommand.Execute(transDraft.Sentences[0]);
                Pump(window);
                var rightMarginScroll = FindVisualChildren<ScrollViewer>(window)
                    .First(sv => sv.IsVisible && FindVisualChildren<ItemsControl>(sv)
                        .Any(ic => ReferenceEquals(ic.ItemsSource, transDraft.SentenceHints)));
                Assert.True(rightMarginScroll.ScrollableHeight > 0 || rightMarginScroll.ExtentHeight > 0);

                // Check REV-H07: IsAsking hides ShowMarks/ShowThread and puts opaque overlay on top.
                model.ShowTabCommand.Execute(Book.Essays);
                Pump(window);
                var reviewed = model.Page!;
                Assert.True(reviewed.HasReview);
                reviewed.IsAsking = true;
                Pump(window);
                Assert.False(reviewed.ShowMarks);
                Assert.False(reviewed.ShowThread);
                Assert.False(reviewed.ShowDraftMargin);
                var askingBorder = FindVisualChildren<Border>(window)
                    .First(b => b.IsVisible && FindVisualChildren<TextBlock>(b).Any(t => t.Text == "老师正在批改…"));
                Assert.NotNull(askingBorder.Background);
                reviewed.IsAsking = false;

                // Resize to 1440x900 and verify clean layout + bitmap render.
                window.Width = 1440;
                window.Height = 900;
                Pump(window);
                foreach (Book tab in Enum.GetValues<Book>())
                {
                    model.ShowTabCommand.Execute(tab);
                    Pump(window);
                    RenderRootBitmap(window);
                }

                // Verify Teacher.Idle locks settings controls.
                model.ShowTabCommand.Execute(Book.Settings);
                Pump(window);
                model.Teacher.Busy = true;
                Pump(window);
                Assert.False(keyBox.IsEnabled);
                var saveButton = FindVisualChildren<Button>(window).Single(b => Equals(b.Content, "保存"));
                Assert.False(saveButton.IsEnabled);
                model.Teacher.Busy = false;

                // Verify Message / WordsToast display on Review, Settings, and Words.
                model.Message = "后台批改通知测试";
                model.ShowTabCommand.Execute(Book.Words);
                Pump(window);
                Assert.Contains(FindVisualChildren<TextBlock>(window), t => t.IsVisible && t.Text == "后台批改通知测试");
                model.ShowTabCommand.Execute(Book.Review);
                Pump(window);
                Assert.Contains(FindVisualChildren<TextBlock>(window), t => t.IsVisible && t.Text == "后台批改通知测试");
                model.ShowTabCommand.Execute(Book.Settings);
                Pump(window);
                Assert.Contains(FindVisualChildren<TextBlock>(window), t => t.IsVisible && t.Text == "后台批改通知测试");

                Assert.Empty(bindingErrors);
            }
            finally
            {
                window.Close();
            }
        }
        finally
        {
            PresentationTraceSources.DataBindingSource.Listeners.Remove(listener);
        }
    });

    [Fact]
    public Task OversizedTranslationDualHintsDoNotClipLeftAt1100() => wpf.RunAsync(() =>
    {
        var dbPath = Path.Combine(_dir.FullName, "oversized-hints.db");
        using var book = Notebook.Open(dbPath, TimeProvider.System);
        var wordsText = string.Join('\n', Enumerable.Range(1, 18)
            .Select(i => $"核心词汇 {i} → key expression with detailed usage notes and contextual collocation {i}"));
        var frameText = string.Join('\n', Enumerable.Range(1, 12)
            .Select(i => $"句式结构说明 {i}: It is through persistent effort and careful reflection that learners master complex ideas."));
        var trans = book.Write(book.NewPage(PieceKind.Translation).Id, "Oversized Dual Hints",
            "第一句中文原文用于测试超长双条提示在窄屏下的滚动与左边缘对齐。第二句中文原文。",
            "The first sentence.");
        book.SaveHint(trans.Id, 1, "第一句中文原文用于测试超长双条提示在窄屏下的滚动与左边缘对齐。", HintKind.Words, wordsText, "Test");
        book.SaveHint(trans.Id, 1, "第一句中文原文用于测试超长双条提示在窄屏下的滚动与左边缘对齐。", HintKind.Frame, frameText, "Test");

        var bindingErrors = new List<string>();
        var listener = new RecordingTraceListener(bindingErrors);
        PresentationTraceSources.Refresh();
        PresentationTraceSources.DataBindingSource.Listeners.Add(listener);
        PresentationTraceSources.DataBindingSource.Switch.Level = SourceLevels.Warning;

        try
        {
            var settings = new TeacherSettings(_dir.FullName);
            var model = new MainViewModel(book, dbPath, settings);
            var window = CreateOffScreenWindow(model, 1100, 640);
            try
            {
                window.Show();
                model.ShowTabCommand.Execute(Book.Translations);
                Pump(window);

                var page = model.Page!;
                page.SelectSentenceCommand.Execute(page.Sentences[0]);
                Pump(window);

                var marginScroll = FindVisualChildren<ScrollViewer>(window)
                    .First(sv => sv.IsVisible && FindVisualChildren<ItemsControl>(sv)
                        .Any(ic => ReferenceEquals(ic.ItemsSource, page.SentenceHints)));
                Assert.True(marginScroll.ScrollableHeight > 0, $"Expected ScrollableHeight > 0, got {marginScroll.ScrollableHeight}");

                marginScroll.ScrollToEnd();
                Pump(window);

                var hintBorder = FindVisualChildren<Border>(marginScroll)
                    .First(b => FindVisualChildren<ItemsControl>(b).Any(ic => ReferenceEquals(ic.ItemsSource, page.SentenceHints)));
                var borderTopLeftX = hintBorder.TransformToAncestor(marginScroll).Transform(new Point(0, 0)).X;
                var borderBottomLeftX = hintBorder.TransformToAncestor(marginScroll).Transform(new Point(0, hintBorder.ActualHeight)).X;
                Assert.True(borderTopLeftX >= 0, $"Hint border top-left clipped: X={borderTopLeftX}");
                Assert.True(borderBottomLeftX >= 0, $"Hint border bottom-left clipped: X={borderBottomLeftX}");

                var collectButtons = FindVisualChildren<Button>(marginScroll)
                    .Where(b => Equals(b.Content, "＋ 收进单词本"))
                    .ToList();
                Assert.Equal(2, collectButtons.Count);
                foreach (var button in collectButtons)
                {
                    var btnTopLeftX = button.TransformToAncestor(marginScroll).Transform(new Point(0, 0)).X;
                    var btnBottomLeftX = button.TransformToAncestor(marginScroll).Transform(new Point(0, button.ActualHeight)).X;
                    Assert.True(btnTopLeftX >= 0, $"Collect button top-left clipped: X={btnTopLeftX}");
                    Assert.True(btnBottomLeftX >= 0, $"Collect button bottom-left clipped: X={btnBottomLeftX}");
                }

                Assert.Empty(bindingErrors);
            }
            finally
            {
                window.Close();
            }
        }
        finally
        {
            PresentationTraceSources.DataBindingSource.Listeners.Remove(listener);
        }
    });

    private static MainWindow CreateOffScreenWindow(MainViewModel model, double width, double height) =>
        new()
        {
            DataContext = model,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = -32000,
            Top = -32000,
            Width = width,
            Height = height,
            ShowActivated = false,
            ShowInTaskbar = false,
        };

    private static void Pump(Window window)
    {
        window.UpdateLayout();
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, new Action(() => frame.Continue = false));
        Dispatcher.PushFrame(frame);
        window.UpdateLayout();
    }

    private static void RenderRootBitmap(Window window)
    {
        var root = (Visual)VisualTreeHelper.GetChild(window, 0);
        var bounds = VisualTreeHelper.GetDescendantBounds(root);
        var width = Math.Max(1, (int)Math.Ceiling(bounds.Width));
        var height = Math.Max(1, (int)Math.Ceiling(bounds.Height));
        var rtb = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        rtb.Render(root);
        var pixels = new byte[4];
        rtb.CopyPixels(new Int32Rect(width / 2, height / 2, 1, 1), pixels, 4, 0);
        Assert.True(pixels[3] > 0);
    }

    private static void SeedNotebook(Notebook book)
    {
        // Seed 12 reviewed pages for 12 LookBack bars, plus a translation draft with hints.
        for (var i = 1; i <= 12; i++)
        {
            var essay = book.Write(book.NewPage(PieceKind.Essay).Id, $"Essay {i}", "Write an essay about study.", "Reading books helps students broaden their horizons and build steady habits.");
            book.HandIn(essay.Id);
            book.SaveReview(essay.Id, new Marking(11,
                "总评内容较长用于测试总评区域的滚动高度限制以及多行文本折行显示是否正常，不会压垮下方批注卡片列表。" + new string('文', 60),
                [new Dimension("内容", "好"), new Dimension("语言", "稳")],
                [new Mark(1, "Reading books", "Reading widely", "表达更地道", Severity.Must, "用词")]),
                "Test Teacher With Long Name For Wrapping Check");
        }

        var trans = book.Write(book.NewPage(PieceKind.Translation).Id, "Translation 1",
            "第一句中文原文。第二句中文原文也很长用于测试滚动。",
            "The first sentence.");
        book.SaveHint(trans.Id, 1, "第一句中文原文。", HintKind.Words, "中文 → Chinese\n原文 → original text", "Test");
        book.SaveHint(trans.Id, 1, "第一句中文原文。", HintKind.Frame, "句式：It is ... that ...", "Test");
        book.AddWord("horizon", "视野", "broaden one's horizon", null);
    }

    private static IEnumerable<T> FindVisualChildren<T>(DependencyObject root) where T : DependencyObject
    {
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is T typed) yield return typed;
            foreach (var grandChild in FindVisualChildren<T>(child)) yield return grandChild;
        }
    }

    private sealed class RecordingTraceListener(List<string> errors) : TraceListener
    {
        public override void Write(string? message)
        {
            if (!string.IsNullOrWhiteSpace(message)) errors.Add(message);
        }

        public override void WriteLine(string? message)
        {
            if (!string.IsNullOrWhiteSpace(message)) errors.Add(message);
        }
    }

    private sealed class ControlKeyboardDevice() : KeyboardDevice(InputManager.Current)
    {
        protected override KeyStates GetKeyStatesFromSystem(Key key) =>
            key == Key.LeftCtrl ? KeyStates.Down : KeyStates.None;
    }
}
