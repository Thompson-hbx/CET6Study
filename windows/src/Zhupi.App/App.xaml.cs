using System.IO;
using System.Windows;
using System.Windows.Media;
using Microsoft.Data.Sqlite;
using Zhupi.App.ViewModels;
using Zhupi.Core;

namespace Zhupi.App;

public partial class App : Application
{
    private const string DataDirOption = "--data-dir=";

    private Notebook? _notebook;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // --data-dir keeps trial runs away from the real notebook.
        var directory = e.Args.FirstOrDefault(a => a.StartsWith(DataDirOption, StringComparison.Ordinal))?[DataDirOption.Length..]
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Zhupi");
        var file = Path.Combine(directory, "study.db");
        try
        {
            _notebook = Notebook.Open(file, TimeProvider.System);
        }
        catch (Exception ex) when (ex is NotebookException or SqliteException or IOException or UnauthorizedAccessException)
        {
            MessageBox.Show($"本子打不开：{ex.Message}", "朱批", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
            return;
        }
        UsePerUserFonts();
        // Packs installed next to the program are this learner's own papers; bringing them in is repeat-safe.
        var packs = ExamPackFolder.ImportAll(_notebook, Path.Combine(AppContext.BaseDirectory, "packs"));
        var model = new MainViewModel(_notebook, file, TeacherSettings.Load(directory))
        {
            Confirm = text => MessageBox.Show(text, "朱批", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel) == MessageBoxResult.OK,
        };
        if (packs.ChangedAnything) model.Message = PackReport(packs);
        new MainWindow { DataContext = model }.Show();
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
