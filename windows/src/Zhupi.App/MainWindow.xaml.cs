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
        TitleBar.Tint(this, ((SolidColorBrush)FindResource("PaperEdge")).Color);
        TitleBar.OfferSnapLayouts(this, MaximizeButton);
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        BorderThickness = WindowState == WindowState.Maximized ? TitleBar.MaximizedOverhang(this) : new Thickness(0);
    }

    private void Minimize(object sender, RoutedEventArgs e) => SystemCommands.MinimizeWindow(this);

    // Reached from the keyboard or UI Automation; mouse clicks on this button are handled in TitleBar.
    private void ToggleMaximize(object sender, RoutedEventArgs e) => TitleBar.ToggleMaximize(this);

    private void CloseWindow(object sender, RoutedEventArgs e) => SystemCommands.CloseWindow(this);

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
