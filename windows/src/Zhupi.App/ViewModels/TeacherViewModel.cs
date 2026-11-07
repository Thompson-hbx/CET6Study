using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

/// <summary>The 请老师 card on the title page. The key itself only passes through <see cref="Save"/>.</summary>
public sealed partial class TeacherViewModel : ObservableObject
{
    private readonly TeacherSettings _settings;

    public TeacherViewModel(TeacherSettings settings)
    {
        _settings = settings;
        Service = settings.Service;
        Address = settings.Service == TeacherService.Compatible ? settings.Address : "";
        Model = settings.Model;
        Note = settings.HasKey ? "已保存 API Key（加密在这台电脑）" : "还没填 API Key";
    }

    public TeacherSettings Settings => _settings;

    public ObservableCollection<string> Models { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsCompatible), nameof(IsDeepSeek))]
    public partial TeacherService Service { get; set; }

    [ObservableProperty]
    public partial string Address { get; set; }

    [ObservableProperty]
    public partial string Model { get; set; }

    [ObservableProperty]
    public partial string Note { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(RefreshModelsCommand))]
    public partial bool Busy { get; set; }

    public bool IsDeepSeek
    {
        get => Service == TeacherService.DeepSeek;
        set { if (value) Service = TeacherService.DeepSeek; }
    }

    public bool IsCompatible
    {
        get => Service == TeacherService.Compatible;
        set { if (value) Service = TeacherService.Compatible; }
    }

    public bool HasKey => _settings.HasKey;

    /// <summary>Called by the view with the password box contents, which then clears the box.</summary>
    public void Save(string key)
    {
        try
        {
            _settings.Save(Service, Address, Model, key);
            Note = Model.Trim().Length == 0 ? "已保存；还差一个模型名" : $"已保存：{_settings.Label}";
        }
        catch (Exception e) when (e is TeacherException or IOException or UnauthorizedAccessException)
        {
            Note = e is TeacherException ? e.Message : "没存上：写不进本子所在的文件夹";
        }
        OnPropertyChanged(nameof(HasKey));
    }

    [RelayCommand]
    private void ForgetKey()
    {
        _settings.ForgetKey();
        Note = "这台电脑上的 API Key 已删除；要撤销 Key 本身，请到服务商网站操作";
        OnPropertyChanged(nameof(HasKey));
    }

    /// <summary>Lists the models this key can use; does not generate anything, so it costs nothing.</summary>
    [RelayCommand(CanExecute = nameof(NotBusy))]
    private async Task RefreshModels()
    {
        Busy = true;
        try
        {
            using var http = ChatTeacher.NewHttpClient();
            var connection = _settings.Connection();
            var models = await new ChatTeacher(http, connection).ModelsAsync(CancellationToken.None);
            Models.Clear();
            foreach (var m in models) Models.Add(m);
            Note = models.Count == 0 ? "服务没有列出模型，可以手动填写" : $"找到 {models.Count} 个模型";
            if (Model.Trim().Length == 0 && models.Count > 0) Model = models[0];
        }
        catch (TeacherException e)
        {
            Note = e.Message;
        }
        finally
        {
            Busy = false;
        }
    }

    private bool NotBusy() => !Busy;
}
