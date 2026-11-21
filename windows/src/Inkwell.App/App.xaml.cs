using System.IO;
using System.Runtime.InteropServices;
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
    private const string DataDirFlag = "--data-dir";
    private const long MaxErrorLogBytes = 1024 * 1024;

    private Notebook? _notebook;
    private FileStream? _dataLease;
    private MainViewModel? _model;

    internal static string? ParseDataDir(string[] args)
    {
        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (arg.StartsWith(DataDirOption, StringComparison.Ordinal))
            {
                var value = arg[DataDirOption.Length..];
                if (string.IsNullOrWhiteSpace(value)) throw new NotebookException("请为 --data-dir 指定一个目录。");
                return value;
            }
            if (arg == DataDirFlag)
            {
                if (i + 1 >= args.Length || string.IsNullOrWhiteSpace(args[i + 1]))
                {
                    throw new NotebookException("请为 --data-dir 指定一个目录。");
                }
                return args[i + 1];
            }
        }
        return null;
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // --data-dir keeps trial runs away from the real notebook.
        try
        {
            var directory = ParseDataDir(e.Args);
            var adopted = false;
            if (directory is null)
            {
                var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                adopted = DataFolder.AdoptFormer(localAppData);
                directory = Path.Combine(localAppData, DataFolder.Name);
            }
            if (string.IsNullOrWhiteSpace(directory)) throw new NotebookException("请为 --data-dir 指定一个目录。");
            directory = Path.GetFullPath(directory);
            Directory.CreateDirectory(directory);
            _dataLease = OpenDataLease(directory);
            var file = Path.Combine(directory, "study.db");
            _notebook = Notebook.Open(file, TimeProvider.System);

            UsePerUserFonts();
            // Packs installed next to the program are this learner's own papers; bringing them in is repeat-safe.
            var packs = ExamPackFolder.ImportAll(_notebook, Path.Combine(AppContext.BaseDirectory, "packs"));
            var model = new MainViewModel(_notebook, file, TeacherSettings.Load(directory))
            {
                Confirm = text => MessageBox.Show(text, "墨水瓶", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel) == MessageBoxResult.OK,
            };
            _model = model;
            if (packs.ChangedAnything) model.Message = PackReport(packs);
            if (adopted) model.Message = $"旧本子已从 {DataFolder.FormerName} 文件夹复制过来，原文件夹保留不动。" + (packs.ChangedAnything ? " " + model.Message : "");
            DispatcherUnhandledException += (_, args) => Unexpected(model, directory, args);
            // Logging off or shutting down does not raise Window.Closing, so the last pause of typing is saved here.
            SessionEnding += (_, args) =>
            {
                if (!model.SaveOpenPage())
                {
                    args.Cancel = true;
                    return;
                }
                model.CancelPendingWork();
                model.CleanupImportTempDirs();
            };
            var window = new MainWindow { DataContext = model };
            MainWindow = window;
            window.Show();
            ShutdownMode = ShutdownMode.OnMainWindowClose;
        }
        catch (Exception ex) when (ex is NotebookException or SqliteException or IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException or InvalidOperationException)
        {
            DisposeStartupResources();
            // A failed copy of the former notebook must stop here: starting on an empty notebook would look like lost work.
            MessageBox.Show($"本子打不开：{ex.Message}", "墨水瓶", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    private void DisposeStartupResources()
    {
        _model = null;
        try
        {
            _notebook?.Dispose();
        }
        catch (Exception)
        {
        }
        _notebook = null;
        try
        {
            _dataLease?.Dispose();
        }
        catch (Exception)
        {
        }
        _dataLease = null;
    }

    private static FileStream OpenDataLease(string directory)
    {
        try
        {
            // Credentials, drafts and the notebook all share this directory for the lifetime of the app.
            return File.Open(Path.Combine(directory, ".inkwell.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException e) when ((e.HResult & 0xffff) is 32 or 33)
        {
            throw new NotebookException("这个本子已在另一个墨水瓶窗口中打开。");
        }
    }

    private const int RepeatLimit = 3;
    private static readonly TimeSpan RepeatWindow = TimeSpan.FromSeconds(30);
    private readonly Queue<DateTimeOffset> _recentErrors = new();
    private bool _reporting;
    private bool _exiting;

    internal bool IsForceShuttingDown => _exiting;

    internal static string AppendBoundedErrorLog(string log, Exception exception, long maxBytes = MaxErrorLogBytes)
    {
        try
        {
            if (File.Exists(log) && new FileInfo(log).Length >= maxBytes)
            {
                File.Move(log, log + ".1", overwrite: true);
            }
            File.AppendAllText(log, $"{DateTimeOffset.Now:O}{Environment.NewLine}{exception}{Environment.NewLine}{Environment.NewLine}");
            return log;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return "（日志也没写上）";
        }
    }

    /// <summary>
    /// The last stop for an error no handler expected: closing the window would lose what is being typed, so the
    /// open page is saved, the details go to errors.log beside the notebook, and the learner is told. An error that
    /// keeps coming back would trap the learner in dialogs, so the app closes once the draft is safe.
    /// </summary>
    private void Unexpected(MainViewModel model, string directory, DispatcherUnhandledExceptionEventArgs args)
    {
        args.Handled = true;
        // The dialog pumps messages, so a repeating error must not stack dialogs or flood errors.log.
        if (_reporting || _exiting) return;
        var log = AppendBoundedErrorLog(Path.Combine(directory, "errors.log"), args.Exception);
        var now = DateTimeOffset.Now;
        _recentErrors.Enqueue(now);
        while (now - _recentErrors.Peek() > RepeatWindow) _recentErrors.Dequeue();
        var repeating = _recentErrors.Count >= RepeatLimit;
        var close = repeating;
        _reporting = true;
        try
        {
            bool saved;
            try
            {
                saved = model.SaveOpenPage();
            }
            catch (Exception)
            {
                // Whatever broke may also break saving; the dialog below then says the text may not be stored.
                saved = false;
            }
            MessageBox.Show(
                (close ? "同一类错误接连出现，墨水瓶要先关掉。" : "出了一个没想到的错误。") + $"\n{args.Exception.Message}\n\n" +
                (saved ? "正在写的内容已经记在本子上。" : "正在写的内容可能没记上，请先把它复制出来。") +
                $"\n错误详情：{log}",
                "墨水瓶", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            _reporting = false;
        }
        if (close)
        {
            _exiting = true;
            model.CancelPendingWork();
            model.CleanupImportTempDirs();
            Shutdown(1);
        }
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
        try
        {
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Microsoft\Windows\Fonts\");
            if (!Directory.Exists(folder)) return;
            var location = new Uri(folder);
            if (!Fonts.GetFontFamilies(location).Any(f => f.Source.EndsWith("#LXGW WenKai", StringComparison.Ordinal))) return;
            Resources["Hand"] = new FontFamily(location, "Segoe Print, ./#LXGW WenKai, KaiTi, STKaiti");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or FormatException or ArgumentException or NotSupportedException or COMException)
        {
            // Fall back to system fonts if the per-user font folder cannot be inspected.
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            try
            {
                _model?.CancelPendingWork();
                _model?.CleanupImportTempDirs();
            }
            catch (Exception)
            {
            }
            _notebook?.Dispose();
        }
        finally
        {
            _dataLease?.Dispose();
            base.OnExit(e);
        }
    }
}
