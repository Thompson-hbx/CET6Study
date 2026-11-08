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
        new MainWindow { DataContext = new MainViewModel(_notebook, file, TeacherSettings.Load(directory)) }.Show();
    }

    /// <summary>
    /// WPF's system font list leaves out fonts installed for the current user only, which is how LXGW WenKai is
    /// installed, so the handwriting family is pointed at that folder when the font is there.
    /// </summary>
    public void UsePerUserFonts()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Microsoft\Windows\Fonts\");
        if (!Directory.Exists(folder)) return;
        var location = new Uri(folder);
        if (!Fonts.GetFontFamilies(location).Any(f => f.Source.EndsWith("#LXGW WenKai", StringComparison.Ordinal))) return;
        Resources["Hand"] = new FontFamily(location, "./#LXGW WenKai, KaiTi, STKaiti, Microsoft YaHei UI");
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _notebook?.Dispose();
        base.OnExit(e);
    }
}
