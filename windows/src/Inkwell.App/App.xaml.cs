using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Inkwell.App.ViewModels;
using Inkwell.Core;
using Microsoft.Data.Sqlite;

namespace Inkwell.App;

public partial class App : Application
{
    private const string DataDirOption = "--data-dir=";

    private Notebook? _notebook;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // --data-dir keeps trial runs away from the real notebook.
        var directory = e.Args.FirstOrDefault(a => a.StartsWith(DataDirOption, StringComparison.Ordinal))?[DataDirOption.Length..];
        var adopted = false;
        try
        {
            if (directory is null)
            {
                var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                adopted = DataFolder.AdoptFormer(localAppData);
                directory = Path.Combine(localAppData, DataFolder.Name);
            }
            _notebook = Notebook.Open(Path.Combine(directory, "study.db"), TimeProvider.System);
        }
        catch (Exception ex) when (ex is NotebookException or SqliteException or IOException or UnauthorizedAccessException)
        {
            // A failed copy of the former notebook must stop here: starting on an empty notebook would look like lost work.
            MessageBox.Show($"本子打不开：{ex.Message}", "墨水瓶", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
            return;
        }
        UsePerUserFonts();
        // Packs installed next to the program are this learner's own papers; bringing them in is repeat-safe.
        var packs = ExamPackFolder.ImportAll(_notebook, Path.Combine(AppContext.BaseDirectory, "packs"));
        var file = Path.Combine(directory, "study.db");
        var model = new MainViewModel(_notebook, file, TeacherSettings.Load(directory))
        {
            Confirm = text => MessageBox.Show(text, "墨水瓶", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel) == MessageBoxResult.OK,
        };
        if (packs.ChangedAnything) model.Message = PackReport(packs);
        if (adopted) model.Message = $"旧本子已从 {DataFolder.FormerName} 文件夹复制过来，原文件夹保留不动。" + (packs.ChangedAnything ? " " + model.Message : "");
        DispatcherUnhandledException += (_, args) => Unexpected(model, directory, args);
        // Logging off or shutting down does not raise Window.Closing, so the last pause of typing is saved here.
        SessionEnding += (_, _) => model.SaveOpenPage();
        new MainWindow { DataContext = model }.Show();
    }

    private const int RepeatLimit = 3;
    private static readonly TimeSpan RepeatWindow = TimeSpan.FromSeconds(30);
    private readonly Queue<DateTimeOffset> _recentErrors = new();
    private bool _reporting;

    /// <summary>
    /// The last stop for an error no handler expected: closing the window would lose what is being typed, so the
    /// open page is saved, the details go to errors.log beside the notebook, and the learner is told. An error that
    /// keeps coming back (from layout or a timer, say) would trap the learner in dialogs, so then the app closes.
    /// </summary>
    private void Unexpected(MainViewModel model, string directory, DispatcherUnhandledExceptionEventArgs args)
    {
        args.Handled = true;
        var log = Path.Combine(directory, "errors.log");
        try
        {
            File.AppendAllText(log, $"{DateTimeOffset.Now:O}{Environment.NewLine}{args.Exception}{Environment.NewLine}{Environment.NewLine}");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log = "（日志也没写上）";
        }
        // The dialog pumps messages, so a repeating error must not stack dialogs on top of each other.
        if (_reporting) return;
        var now = DateTimeOffset.Now;
        _recentErrors.Enqueue(now);
        while (now - _recentErrors.Peek() > RepeatWindow) _recentErrors.Dequeue();
        var repeating = _recentErrors.Count >= RepeatLimit;
        _reporting = true;
        try
        {
            var saved = true;
            try
            {
                model.SaveOpenPage();
            }
            catch (Exception)
            {
                // Whatever broke may also break saving; the dialog below then says the text may not be stored.
                saved = false;
            }
            MessageBox.Show(
                (repeating ? "同一类错误接连出现，墨水瓶要先关掉。" : "出了一个没想到的错误。") + $"\n{args.Exception.Message}\n\n" +
                (saved ? "正在写的内容已经记在本子上。" : "正在写的内容可能没记上，请先把它复制出来。") +
                $"\n错误详情：{log}",
                "墨水瓶", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            _reporting = false;
        }
        if (repeating) Shutdown(1);
    }

    private static string PackReport(FolderImport packs)
    {
        var parts = new List<string>();
        if (packs.Added > 0) parts.Add($"新增 {packs.Added} 套");
        if (packs.Updated > 0) parts.Add($"更新 {packs.Updated} 套");
        if (packs.Skipped.Count > 0) parts.Add($"跳过 {packs.Skipped.Count} 套（{string.Join("；", packs.Skipped.Take(2).Select(k => $"{k.Id} {k.Reason}"))}）");
        if (packs.Unreadable.Count > 0) parts.Add($"读不了 {string.Join("、", packs.Unreadable)}");
        return "套卷：" + string.Join("，", parts);
    }

    /// <summary>
    /// WPF's system font list leaves out fonts installed for the current user only, which is how LXGW WenKai is
    /// installed, so the handwriting family's Chinese fallback is pointed at that folder when the font is there.
    /// </summary>
    public void UsePerUserFonts()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Microsoft\Windows\Fonts\");
        if (!Directory.Exists(folder)) return;
        var location = new Uri(folder);
        if (!Fonts.GetFontFamilies(location).Any(f => f.Source.EndsWith("#LXGW WenKai", StringComparison.Ordinal))) return;
        Resources["Hand"] = new FontFamily(location, "Segoe Print, ./#LXGW WenKai, KaiTi, STKaiti");
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _notebook?.Dispose();
        base.OnExit(e);
    }
}
