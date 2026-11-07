using System.IO;
using System.Windows;
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
        new MainWindow { DataContext = new MainViewModel(_notebook, file) }.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _notebook?.Dispose();
        base.OnExit(e);
    }
}
