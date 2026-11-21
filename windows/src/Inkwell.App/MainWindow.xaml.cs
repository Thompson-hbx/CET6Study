using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Inkwell.App.ViewModels;
using Microsoft.Win32;

namespace Inkwell.App;

public partial class MainWindow : Window
{
    private MainViewModel? _observedModel;

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnMainDataContextChanged;
    }

    private MainViewModel Model => (MainViewModel)DataContext;

    private void OnMainDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (_observedModel is not null)
        {
            _observedModel.PropertyChanged -= OnModelPropertyChanged;
            _observedModel.Teacher.PropertyChanged -= OnTeacherPropertyChanged;
        }
        _observedModel = e.NewValue as MainViewModel;
        if (_observedModel is not null)
        {
            _observedModel.PropertyChanged += OnModelPropertyChanged;
            _observedModel.Teacher.PropertyChanged += OnTeacherPropertyChanged;
        }
    }

    private void OnModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.Tab) && _observedModel?.Tab != Book.Settings)
        {
            KeyBox.Clear();
        }
    }

    private void OnTeacherPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(TeacherViewModel.Service))
        {
            KeyBox.Clear();
        }
    }

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

    private void SearchBoxPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return && e.KeyboardDevice.Modifiers == ModifierKeys.Control)
        {
            e.Handled = true;
        }
    }

    private async void ChooseOldFile(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择旧手机数据文件（cet6_study.db）",
            Filter = "旧数据文件|cet6_study.db;*.db|所有文件|*.*",
        };
        if (dialog.ShowDialog(this) == true) await Model.ImportOldAsync(dialog.FileName);
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

    private void ExportPage(object sender, RoutedEventArgs e)
    {
        if (Model.VisiblePage is not { } page) return;
        var dialog = new SaveFileDialog
        {
            Title = "导出当前稿件",
            Filter = "Markdown 文件 (*.md)|*.md",
            DefaultExt = ".md",
            FileName = page.MarkdownFileName,
            OverwritePrompt = true,
        };
        if (dialog.ShowDialog(this) == true) page.ExportMarkdown(dialog.FileName);
    }

    // The key only travels from the password box into DPAPI storage; the box is emptied right after.
    private void SaveTeacher(object sender, RoutedEventArgs e)
    {
        if (!Model.Teacher.Idle) return;
        Model.Teacher.Save(KeyBox.Password);
        KeyBox.Clear();
    }

    private void OpenDataFolder(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Path.GetDirectoryName(Model.DataFile)}\"") { UseShellExecute = true });

    // Typing is saved after a pause; closing must not lose the last pause's worth of text.
    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        if (e.Cancel) return;
        if ((Application.Current as App)?.IsForceShuttingDown == true)
        {
            Model.CancelPendingWork();
            return;
        }
        if (!Model.SaveOpenPage())
        {
            var discard = MessageBox.Show(
                this,
                "当前正在写的草稿写不进本子，关闭窗口会丢失还没保存的修改。还是要放弃修改并关闭吗？",
                "墨水瓶",
                MessageBoxButton.OKCancel,
                MessageBoxImage.Warning,
                MessageBoxResult.Cancel) == MessageBoxResult.OK;
            if (!discard)
            {
                e.Cancel = true;
                return;
            }
        }
        if (Model.HasPendingWork)
        {
            var confirmPending = MessageBox.Show(
                this,
                $"以下操作还没完成，现在关闭会取消它们：\n{string.Join("\n", Model.PendingWorkLines())}\n\n确定要关闭墨水瓶吗？",
                "墨水瓶",
                MessageBoxButton.OKCancel,
                MessageBoxImage.Warning,
                MessageBoxResult.Cancel) == MessageBoxResult.OK;
            if (!confirmPending)
            {
                e.Cancel = true;
                return;
            }
        }
        Model.CancelPendingWork();
    }
}
