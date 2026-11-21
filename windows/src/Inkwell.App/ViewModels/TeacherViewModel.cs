using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Inkwell.Core.ChatGpt;
using Inkwell.Core.Teacher;

namespace Inkwell.App.ViewModels;

/// <summary>The 请老师 card on the title page. An API key only passes through <see cref="Save"/>; ChatGPT tokens never reach it.</summary>
public sealed partial class TeacherViewModel : ObservableObject
{
    private static readonly TimeSpan SignInWait = TimeSpan.FromMinutes(5);

    private readonly TeacherSettings _settings;
    private readonly Func<HttpClient> _httpFactory;
    private readonly Action<Uri> _openBrowser;
    private readonly TimeProvider _clock;
    private CancellationTokenSource? _signingIn;
    private CancellationTokenSource? _signingOut;
    private CancellationTokenSource? _refreshing;

    public TeacherViewModel(TeacherSettings settings)
        : this(settings, ChatTeacher.NewHttpClient, OpenSystemBrowser, TimeProvider.System)
    {
    }

    internal TeacherViewModel(TeacherSettings settings, Func<HttpClient> httpFactory, Action<Uri> openBrowser, TimeProvider clock)
    {
        _settings = settings;
        _httpFactory = httpFactory;
        _openBrowser = openBrowser;
        _clock = clock;
        Address = settings.Service == TeacherService.Compatible ||
                  (settings.Service == TeacherService.ChatGpt &&
                   !string.Equals(settings.Address, TeacherConnection.DeepSeekAddress, StringComparison.OrdinalIgnoreCase))
            ? settings.Address
            : "";
        Model = "";
        Note = "";
        Service = settings.Service;
        Model = settings.Service == TeacherService.ChatGpt ? settings.ChatGptModel : settings.Model;
        Depth = settings.Depth;
        Note = settings.Service == TeacherService.ChatGpt ? AccountNote() : HasKey ? "已保存 API Key（加密在这台电脑）" : "还没填 API Key";
    }

    internal TimeSpan RevokeTimeout { get; init; } = TimeSpan.FromSeconds(5);

    internal TimeSpan SignInTimeout { get; init; } = SignInWait;

    private static void OpenSystemBrowser(Uri url) =>
        Process.Start(new ProcessStartInfo(url.ToString()) { UseShellExecute = true });

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
    [NotifyCanExecuteChangedFor(nameof(RefreshModelsCommand), nameof(SignInCommand), nameof(SignOutCommand), nameof(ForgetKeyCommand))]
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

    public bool HasKey => UsesKey && _settings.HasKeyFor(Service, Address);

    private bool CanForgetKey() => Idle && HasKey;

    public bool SignedIn => _settings.Vault.Load() is not null;

    public string SignInLabel => SignedIn ? "重新登录 / 换账号" : "使用 ChatGPT 登录";

    public string Account => _settings.Vault.Load() is { } s
        ? $"已登录 {(s.Email.Length > 0 ? s.Email : "ChatGPT 账号")}{(s.CanUsePlan ? "，可以用套餐额度" : "，但没有授权使用套餐额度")}"
        : "还没登录 ChatGPT";

    partial void OnServiceChanged(TeacherService value)
    {
        Models.Clear();
        Model = value == TeacherService.ChatGpt ? _settings.ChatGptModel : _settings.Model;
        OnPropertyChanged(nameof(HasKey));
        ForgetKeyCommand.NotifyCanExecuteChanged();
        Note = value == TeacherService.ChatGpt ? AccountNote() : HasKey ? "已保存 API Key（加密在这台电脑）" : "还没填 API Key";
    }

    partial void OnAddressChanged(string value)
    {
        if (Service != TeacherService.Compatible) return;
        OnPropertyChanged(nameof(HasKey));
        ForgetKeyCommand.NotifyCanExecuteChanged();
    }

    /// <summary>Called by the view with the password box contents, which then clears the box.</summary>
    public void Save(string key)
    {
        if (Busy) return;
        try
        {
            _settings.Save(Service, Address, Model, key, Depth);
            if (Service == TeacherService.Compatible) Address = _settings.Address;
            Model = Service == TeacherService.ChatGpt ? _settings.ChatGptModel : _settings.Model;
            Note = Model.Length == 0 ? "已保存；还差一个模型名" : $"已保存：{_settings.Label}";
        }
        catch (Exception e) when (e is TeacherException or IOException or UnauthorizedAccessException)
        {
            Note = e is TeacherException ? e.Message : "没存上：写不进本子所在的文件夹";
        }
        OnPropertyChanged(nameof(HasKey));
        ForgetKeyCommand.NotifyCanExecuteChanged();
    }

    [RelayCommand(CanExecute = nameof(CanForgetKey))]
    private void ForgetKey()
    {
        if (!CanForgetKey()) return;
        try
        {
            _settings.ForgetKey();
            Note = "这台电脑上的 API Key 已删除；要撤销 Key 本身，请到服务商网站操作";
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Note = "没能删除本机上的 API Key，请检查本子所在文件夹后重试。";
        }
        OnPropertyChanged(nameof(HasKey));
        ForgetKeyCommand.NotifyCanExecuteChanged();
    }

    /// <summary>
    /// Listens on 127.0.0.1 first, then opens the system browser at ChatGPT's sign-in page and waits for the
    /// browser to come back. Nothing is typed into this app; the password stays with ChatGPT.
    /// </summary>
    [RelayCommand(CanExecute = nameof(Idle))]
    private async Task SignIn()
    {
        if (Busy) return;
        Busy = true;
        WaitingForBrowser = true;
        using var signingIn = new CancellationTokenSource(SignInTimeout, _clock);
        _signingIn = signingIn;
        var vault = _settings.Vault;
        SignInAttempt? attempt = null;
        try
        {
            ChatGptSession? previous;
            string hostId;
            string? rememberedClientId;
            string? rememberedIdToken;
            lock (vault)
            {
                previous = vault.Load();
                hostId = vault.HostId;
                rememberedClientId = vault.RememberedClientId;
                rememberedIdToken = vault.RememberedIdToken;
            }
            IReadOnlyDictionary<string, string> callback;
            try
            {
                using var loopback = new LoopbackCallback();
                attempt = ChatGptAuth.Begin(loopback.RedirectUri, hostId, rememberedClientId, rememberedIdToken, previous?.Email);
                Note = "已在浏览器打开 ChatGPT 登录页，登录并同意授权后回到这里…";
                _openBrowser(attempt.AuthorizeUrl);
                callback = await loopback.WaitAsync(attempt.State, signingIn.Token);
            }
            catch (Exception e) when (e is IOException or SocketException or Win32Exception or InvalidOperationException)
            {
                Note = "没能打开浏览器或本机端口，没有登录。";
                return;
            }
            WaitingForBrowser = false;
            Note = "正在确认登录…";
            using var http = _httpFactory();
            var session = await new ChatGptAuth(http, _clock).FinishAsync(attempt, callback, signingIn.Token);
            var switched = previous is not null && previous.Subject != session.Subject;
            try
            {
                lock (vault)
                {
                    vault.Save(session);
                    vault.RememberedClientId = session.ClientId;
                    vault.RememberedIdToken = session.IdToken;
                }
                if (IsChatGpt)
                {
                    _settings.SaveChatGpt(Model, Depth);
                    Model = _settings.ChatGptModel;
                }
                Note = switched ? "这次登录的是另一个 ChatGPT 账号，已切换过去。" : AccountNote();
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or CryptographicException)
            {
                Note = "ChatGPT 登录成功了，但没能把登录状态存到这台电脑上，请检查本子所在文件夹后重试。";
            }
        }
        catch (OperationCanceledException)
        {
            Note = "登录已取消或等太久了，没有登录。";
        }
        catch (ChatGptException e)
        {
            if (e.ForgetClient && attempt is not null)
            {
                try
                {
                    lock (vault)
                    {
                        if (vault.RememberedClientId == attempt.ClientId)
                        {
                            vault.RememberedClientId = null;
                            vault.RememberedIdToken = null;
                        }
                    }
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException)
                {
                }
            }
            Note = e.Message;
        }
        finally
        {
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

    /// <summary>Ends the local session before attempting remote revocation.</summary>
    [RelayCommand(CanExecute = nameof(Idle))]
    private async Task SignOut()
    {
        if (Busy) return;
        Busy = true;
        Note = "正在退出 ChatGPT…";
        using var signingOut = new CancellationTokenSource();
        _signingOut = signingOut;
        try
        {
            var session = await ChatGptTeacher.EndSessionAsync(_settings.Vault, signingOut.Token);
            if (session is null)
            {
                Note = AccountNote();
                return;
            }
            using var http = _httpFactory();
            using var revokeCts = CancellationTokenSource.CreateLinkedTokenSource(signingOut.Token);
            revokeCts.CancelAfter(RevokeTimeout);
            bool revoked;
            try
            {
                revoked = await new ChatGptAuth(http, _clock).RevokeAsync(session, revokeCts.Token);
            }
            catch (OperationCanceledException)
            {
                revoked = false;
            }
            Note = revoked
                ? "已退出，并让 OpenAI 撤销了这次授权。"
                : "已在本机退出，但没能确认 OpenAI 那边已撤销；可以到 ChatGPT 设置里断开墨水瓶。";
        }
        catch (OperationCanceledException)
        {
            Note = AccountNote();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or CryptographicException)
        {
            Note = "没能在本机清除登录状态，请检查本子所在文件夹后重试。";
        }
        finally
        {
            _signingOut = null;
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
        if (Busy) return;
        Busy = true;
        using var refreshing = new CancellationTokenSource();
        _refreshing = refreshing;
        try
        {
            using var http = _httpFactory();
            IReadOnlyList<string> models;
            if (Service == TeacherService.ChatGpt)
            {
                models = (await new ChatGptTeacher(http, new ChatGptAuth(http, _clock), _settings.Vault, _clock, "")
                    .ModelsAsync(refreshing.Token)).Select(m => m.Slug).ToList();
            }
            else
            {
                var connection = _settings.ConnectionFor(Service, Address, Model);
                models = await new ChatTeacher(http, connection).ModelsAsync(refreshing.Token);
                if (Service == TeacherService.Compatible) Address = connection.Address.ToString();
            }
            Model = Model.Trim();
            Models.Clear();
            foreach (var m in models) Models.Add(m);
            Note = models.Count == 0 ? "没有列出可用的模型，可以手动填写" : $"找到 {models.Count} 个模型，选一个后点「保存」";
            if (Model.Length == 0 && models.Count > 0) Model = models[0];
        }
        catch (OperationCanceledException)
        {
            Note = "已取消查找可用模型。";
        }
        catch (Exception e) when (e is TeacherException or ChatGptException)
        {
            Note = e.Message;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or CryptographicException)
        {
            Note = "读取或保存登录状态失败，请检查本子所在文件夹后重试。";
        }
        finally
        {
            _refreshing = null;
            Busy = false;
            OnPropertyChanged(nameof(SignedIn));
            OnPropertyChanged(nameof(SignInLabel));
            OnPropertyChanged(nameof(Account));
        }
    }

    public void CancelPending()
    {
        _signingIn?.Cancel();
        _signingOut?.Cancel();
        _refreshing?.Cancel();
    }

    private string AccountNote() => _settings.Vault.Load() is null ? "" : Account;
}

/// <summary>One option in the 批改深度 picker.</summary>
public sealed record DepthChoice(Depth Value, string Name);
