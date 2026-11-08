using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using Zhupi.App.ViewModels;

namespace Zhupi.App;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    private MainViewModel Model => (MainViewModel)DataContext;

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        TitleBar.Tint(this, Colour("Desk"), Colour("Ink"), Colour("PaperEdge"));
    }

    private Color Colour(string key) => ((SolidColorBrush)FindResource(key)).Color;

    private void FocusSearch(object sender, ExecutedRoutedEventArgs e)
    {
        SearchBox.Focus();
        SearchBox.SelectAll();
    }

    private void ChooseOldFile(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择旧手机数据文件（cet6_study.db）",
            Filter = "旧数据文件|cet6_study.db;*.db|所有文件|*.*",
        };
        if (dialog.ShowDialog(this) == true) Model.ImportOld(dialog.FileName);
    }

    private void ChoosePack(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择真题内容包（CET6ExamPack JSON）",
            Filter = "内容包|*.json|所有文件|*.*",
        };
        if (dialog.ShowDialog(this) == true) Model.Papers.Import(dialog.FileName);
    }

    // The key only travels from the password box into DPAPI storage; the box is emptied right after.
    private void SaveTeacher(object sender, RoutedEventArgs e)
    {
        Model.Teacher.Save(KeyBox.Password);
        KeyBox.Clear();
    }

    private void OpenDataFolder(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Path.GetDirectoryName(Model.DataFile)}\"") { UseShellExecute = true });

    // Typing is saved after a pause; closing must not lose the last pause's worth of text.
    protected override void OnClosing(CancelEventArgs e)
    {
        Model.SaveOpenPage();
        base.OnClosing(e);
    }
}
