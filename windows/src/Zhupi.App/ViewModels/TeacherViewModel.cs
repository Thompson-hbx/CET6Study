using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Zhupi.Core.ChatGpt;
using Zhupi.Core.Teacher;

namespace Zhupi.App.ViewModels;

/// <summary>The 请老师 card on the title page. An API key only passes through <see cref="Save"/>; ChatGPT tokens never reach it.</summary>
public sealed partial class TeacherViewModel : ObservableObject
{
    private static readonly TimeSpan SignInWait = TimeSpan.FromMinutes(5);

    private readonly TeacherSettings _settings;
    private CancellationTokenSource? _signingIn;

    public TeacherViewModel(TeacherSettings settings)
    {
        _settings = settings;
        Address = settings.Service == TeacherService.Compatible ? settings.Address : "";
        Model = "";
        Note = "";
        Service = settings.Service;
        Model = settings.Service == TeacherService.ChatGpt ? settings.ChatGptModel : settings.Model;
        Depth = settings.Depth;
        Note = settings.Service == TeacherService.ChatGpt ? AccountNote() : settings.HasKey ? "已保存 API Key（加密在这台电脑）" : "还没填 API Key";
    }

    public ObservableCollection<string> Models { get; } = [];

    public IReadOnlyList<DepthChoice> Depths { get; } =
    [
        new(Depth.Default, "默认"),
        new(Depth.Low, "浅"),
        new(Depth.Medium, "中"),
        new(Depth.High, "深"),
        new(Depth.Max, "最深"),
    ];

    [ObservableProperty]
    public partial Depth Depth { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsCompatible), nameof(IsDeepSeek), nameof(IsChatGpt), nameof(UsesKey))]
    public partial TeacherService Service { get; set; }

    [ObservableProperty]
    public partial string Address { get; set; }

    [ObservableProperty]
    public partial string Model { get; set; }

    [ObservableProperty]
    public partial string Note { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Idle))]
    [NotifyCanExecuteChangedFor(nameof(RefreshModelsCommand), nameof(SignInCommand), nameof(SignOutCommand))]
    public partial bool Busy { get; set; }

    [ObservableProperty]
    public partial bool WaitingForBrowser { get; set; }

    public bool Idle => !Busy;

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

    public bool IsChatGpt
    {
        get => Service == TeacherService.ChatGpt;
        set { if (value) Service = TeacherService.ChatGpt; }
    }

    public bool UsesKey => !IsChatGpt;

    public bool HasKey => _settings.HasKey;

    public bool SignedIn => _settings.Vault.Load() is not null;

    public string SignInLabel => SignedIn ? "重新登录 / 换账号" : "使用 ChatGPT 登录";

    public string Account => _settings.Vault.Load() is { } s
        ? $"已登录 {(s.Email.Length > 0 ? s.Email : "ChatGPT 账号")}{(s.CanUsePlan ? "，可以用套餐额度" : "，但没有授权使用套餐额度")}"
        : "还没登录 ChatGPT";

    partial void OnServiceChanged(TeacherService value)
    {
        Models.Clear();
        Model = value == TeacherService.ChatGpt ? _settings.ChatGptModel : _settings.Model;
        Note = value == TeacherService.ChatGpt ? AccountNote() : _settings.HasKey ? "已保存 API Key（加密在这台电脑）" : "还没填 API Key";
    }

    /// <summary>Called by the view with the password box contents, which then clears the box.</summary>
    public void Save(string key)
    {
        try
        {
            _settings.Save(Service, Address, Model, key, Depth);
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

    /// <summary>
    /// Listens on 127.0.0.1 first, then opens the system browser at ChatGPT's sign-in page and waits for the
    /// browser to come back. Nothing is typed into this app; the password stays with ChatGPT.
    /// </summary>
    [RelayCommand(CanExecute = nameof(Idle))]
    private async Task SignIn()
    {
        Busy = true;
        WaitingForBrowser = true;
        _signingIn = new CancellationTokenSource(SignInWait);
        var vault = _settings.Vault;
        try
        {
            using var loopback = new LoopbackCallback();
            var previous = vault.Load();
            var attempt = ChatGptAuth.Begin(loopback.RedirectUri, vault.HostId, vault.RememberedClientId, vault.RememberedIdToken, previous?.Email);
            Note = "已在浏览器打开 ChatGPT 登录页，登录并同意授权后回到这里…";
            Process.Start(new ProcessStartInfo(attempt.AuthorizeUrl.ToString()) { UseShellExecute = true });
            var callback = await loopback.WaitAsync(_signingIn.Token);
            WaitingForBrowser = false;
            Note = "正在确认登录…";
            using var http = ChatTeacher.NewHttpClient();
            var session = await new ChatGptAuth(http, TimeProvider.System).FinishAsync(attempt, callback, _signingIn.Token);
            if (previous is not null && previous.Subject != session.Subject)
            {
                Note = "这次登录的是另一个 ChatGPT 账号，已切换过去。";
            }
            vault.Save(session);
            vault.RememberedClientId = session.ClientId;
            vault.RememberedIdToken = session.IdToken;
            _settings.SaveChatGpt(Model, Depth);
            Note = AccountNote();
        }
        catch (OperationCanceledException)
        {
            Note = "登录已取消或等太久了，没有登录。";
        }
        catch (Exception e) when (e is ChatGptException or IOException or System.Net.Sockets.SocketException or System.ComponentModel.Win32Exception)
        {
            Note = e is ChatGptException ? e.Message : "没能打开浏览器或本机端口，没有登录。";
        }
        finally
        {
            _signingIn?.Dispose();
            _signingIn = null;
            WaitingForBrowser = false;
            Busy = false;
            OnPropertyChanged(nameof(SignedIn));
            OnPropertyChanged(nameof(SignInLabel));
            OnPropertyChanged(nameof(Account));
        }
    }

    [RelayCommand]
    private void CancelSignIn() => _signingIn?.Cancel();

    /// <summary>Revokes the refresh token at OpenAI, then forgets the tokens here either way.</summary>
    [RelayCommand(CanExecute = nameof(Idle))]
    private async Task SignOut()
    {
        if (_settings.Vault.Load() is not { } session) return;
        Busy = true;
        try
        {
            using var http = ChatTeacher.NewHttpClient();
            var revoked = await new ChatGptAuth(http, TimeProvider.System).RevokeAsync(session, CancellationToken.None);
            _settings.Vault.Clear();
            // The ID token is only a re-sign-in hint; after signing out it must not be sent again.
            _settings.Vault.RememberedIdToken = null;
            Note = revoked
                ? "已退出，并让 OpenAI 撤销了这次授权。"
                : "已在本机退出，但没能确认 OpenAI 那边已撤销；可以到 ChatGPT 设置里断开朱批。";
        }
        finally
        {
            Busy = false;
            OnPropertyChanged(nameof(SignedIn));
            OnPropertyChanged(nameof(SignInLabel));
            OnPropertyChanged(nameof(Account));
        }
    }

    /// <summary>Lists the models this account or key can use; does not generate anything, so it costs nothing.</summary>
    [RelayCommand(CanExecute = nameof(Idle))]
    private async Task RefreshModels()
    {
        Busy = true;
        try
        {
            using var http = ChatTeacher.NewHttpClient();
            IReadOnlyList<string> models = Service == TeacherService.ChatGpt
                ? (await new ChatGptTeacher(http, new ChatGptAuth(http, TimeProvider.System), _settings.Vault, TimeProvider.System, "")
                    .ModelsAsync(CancellationToken.None)).Select(m => m.Slug).ToList()
                : await new ChatTeacher(http, _settings.Connection()).ModelsAsync(CancellationToken.None);
            Models.Clear();
            foreach (var m in models) Models.Add(m);
            Note = models.Count == 0 ? "没有列出可用的模型，可以手动填写" : $"找到 {models.Count} 个模型，选一个后点「保存」";
            if (Model.Trim().Length == 0 && models.Count > 0) Model = models[0];
        }
        catch (Exception e) when (e is TeacherException or ChatGptException)
        {
            Note = e.Message;
        }
        finally
        {
            Busy = false;
            OnPropertyChanged(nameof(Account));
        }
    }

    private string AccountNote() => _settings.Vault.Load() is null ? "" : Account;
}

/// <summary>One option in the 批改深度 picker.</summary>
public sealed record DepthChoice(Depth Value, string Name);
