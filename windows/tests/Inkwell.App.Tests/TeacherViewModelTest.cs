using System.ComponentModel;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Web;
using Inkwell.App.ViewModels;
using Inkwell.Core.ChatGpt;
using Inkwell.Core.Teacher;

namespace Inkwell.App.Tests;

public sealed class TeacherViewModelTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-teacher-vm-");
    private readonly ManualClock _clock = new();
    private readonly RSA _rsa = RSA.Create(2048);

    public void Dispose()
    {
        _rsa.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public async Task SignInSuccessPersistsSessionClientIdAndIdToken()
    {
        var settings = new TeacherSettings(_dir.FullName);
        var lastNonce = "";
        var issuedClientId = "oaiapp_new";
        var vm = CreateVm(
            settings,
            url => TriggerCallback(url, out lastNonce, $"code=code-1&client_id={issuedClientId}"),
            req => HandleOAuth(req, issuedClientId, () => lastNonce, "sub-1", "a@example.com"));
        vm.Service = TeacherService.ChatGpt;

        await vm.SignInCommand.ExecuteAsync(null);

        Assert.True(vm.Idle);
        Assert.False(vm.Busy);
        Assert.False(vm.WaitingForBrowser);
        Assert.True(vm.SignedIn);
        Assert.Equal("重新登录 / 换账号", vm.SignInLabel);
        Assert.Contains("a@example.com", vm.Account);
        Assert.Equal(issuedClientId, settings.Vault.RememberedClientId);
        Assert.False(string.IsNullOrEmpty(settings.Vault.RememberedIdToken));
        Assert.Equal("sub-1", settings.Vault.Load()?.Subject);
    }

    [Fact]
    public async Task SignInAccountSwitchPreservesSwitchNote()
    {
        var settings = new TeacherSettings(_dir.FullName);
        settings.Vault.Save(new ChatGptSession(
            "oaiapp_1", "sub-old", "old@example.com", "old-id", "old-acc", "old-ref",
            _clock.Now.AddHours(1), ["openid", ChatGptSession.PlanScope])
        { GrantId = Guid.NewGuid() });
        settings.Vault.RememberedClientId = "oaiapp_1";

        var lastNonce = "";
        var vm = CreateVm(
            settings,
            url => TriggerCallback(url, out lastNonce, "code=code-2"),
            req => HandleOAuth(req, "oaiapp_1", () => lastNonce, "sub-new", "new@example.com"));
        vm.Service = TeacherService.ChatGpt;

        await vm.SignInCommand.ExecuteAsync(null);

        Assert.Equal("这次登录的是另一个 ChatGPT 账号，已切换过去。", vm.Note);
        Assert.Equal("sub-new", settings.Vault.Load()?.Subject);
    }

    [Fact]
    public async Task SignInInvalidClientCallbackAndTokenErrorForgetClientId_NewH01()
    {
        var settings = new TeacherSettings(_dir.FullName);
        settings.Vault.RememberedClientId = "oaiapp_stale";
        settings.Vault.RememberedIdToken = "stale-id";

        var vmCallback = CreateVm(
            settings,
            url => TriggerCallback(url, out _, "error=invalid_client"),
            _ => throw new InvalidOperationException("Token endpoint should not be called on callback error."));

        await vmCallback.SignInCommand.ExecuteAsync(null);

        Assert.Null(settings.Vault.RememberedClientId);
        Assert.Null(settings.Vault.RememberedIdToken);
        Assert.Contains("重新登录", vmCallback.Note);

        settings.Vault.RememberedClientId = "oaiapp_stale_2";
        settings.Vault.RememberedIdToken = "stale-id-2";
        var vmToken = CreateVm(
            settings,
            url => TriggerCallback(url, out _, "code=code-3"),
            req => req.RequestUri!.AbsolutePath.EndsWith("/oauth/token", StringComparison.Ordinal)
                ? JsonResponse(HttpStatusCode.BadRequest, """{"error":"invalid_client"}""")
                : throw new InvalidOperationException("Unexpected request"));

        await vmToken.SignInCommand.ExecuteAsync(null);

        Assert.Null(settings.Vault.RememberedClientId);
        Assert.Null(settings.Vault.RememberedIdToken);
        Assert.Contains("重新登录", vmToken.Note);
    }

    [Fact]
    public async Task SignInSeparatesBrowserFailureFromStorageFailure()
    {
        var settings = new TeacherSettings(_dir.FullName);
        var vmBrowserErr = CreateVm(
            settings,
            _ => throw new Win32Exception("no browser"),
            _ => throw new InvalidOperationException());

        await vmBrowserErr.SignInCommand.ExecuteAsync(null);
        Assert.Equal("没能打开浏览器或本机端口，没有登录。", vmBrowserErr.Note);

        var vmInvalidOp = CreateVm(
            settings,
            _ => throw new InvalidOperationException("shell failure"),
            _ => throw new InvalidOperationException());

        await vmInvalidOp.SignInCommand.ExecuteAsync(null);
        Assert.Equal("没能打开浏览器或本机端口，没有登录。", vmInvalidOp.Note);

        var lastNonce = "";
        var vmStorageErr = CreateVm(
            settings,
            url => TriggerCallback(url, out lastNonce, "code=code-ok&client_id=oaiapp_ok"),
            req => HandleOAuth(req, "oaiapp_ok", () => lastNonce, "sub-ok", "ok@example.com"));
        vmStorageErr.Service = TeacherService.ChatGpt;
        // Lock chatgpt.session so vault.Save throws IOException after OAuth succeeded.
        using var lockSession = new FileStream(
            Path.Combine(_dir.FullName, "chatgpt.session"), FileMode.Create, FileAccess.ReadWrite, FileShare.None);

        await vmStorageErr.SignInCommand.ExecuteAsync(null);

        Assert.Contains("登录成功了，但没能把登录状态存到这台电脑上", vmStorageErr.Note);
    }

    [Fact]
    public async Task SignOutClearsLocalSessionFirstAndRespectsShortRevokeTimeout()
    {
        var settings = new TeacherSettings(_dir.FullName);
        settings.Vault.Save(new ChatGptSession(
            "oaiapp_1", "sub-1", "a@example.com", "id-tok", "acc-tok", "ref-tok",
            _clock.Now.AddHours(1), ["openid", ChatGptSession.PlanScope])
        { GrantId = Guid.NewGuid() });

        var revokeObservedCancel = false;
        var vm = CreateVm(
            settings,
            _ => { },
            async (req, ct) =>
            {
                if (req.RequestUri!.AbsolutePath.EndsWith("/oauth/revoke", StringComparison.Ordinal))
                {
                    try
                    {
                        await Task.Delay(TimeSpan.FromSeconds(10), ct);
                    }
                    catch (OperationCanceledException)
                    {
                        revokeObservedCancel = true;
                        throw;
                    }
                }
                return new HttpResponseMessage(HttpStatusCode.OK);
            },
            revokeTimeout: TimeSpan.FromMilliseconds(40));

        await vm.SignOutCommand.ExecuteAsync(null);

        Assert.True(revokeObservedCancel);
        Assert.False(vm.SignedIn);
        Assert.Null(settings.Vault.Load());
        Assert.Contains("已在本机退出", vm.Note);
        Assert.True(vm.Idle);
    }

    [Fact]
    public async Task RefreshModelsSyncsCompatibleAddressAndNotifiesAccountOnRevokedGrant()
    {
        var settings = new TeacherSettings(_dir.FullName);
        var vm = CreateVm(
            settings,
            _ => { },
            req =>
            {
                if (req.RequestUri!.ToString() == "https://api.example.com/v1/models")
                {
                    return JsonResponse(HttpStatusCode.OK, """{"data":[{"id":"m-b"},{"id":"m-a"}]}""");
                }
                return JsonResponse(HttpStatusCode.Unauthorized, """{"error":{"code":"invalid_grant"}}""");
            });

        vm.Service = TeacherService.Compatible;
        vm.Address = "https://api.example.com/v1";
        vm.Model = "  m-a  ";
        vm.Save("sk-test-key");

        await vm.RefreshModelsCommand.ExecuteAsync(null);

        Assert.Equal("https://api.example.com/v1/", vm.Address);
        Assert.Equal("m-a", vm.Model);
        Assert.Equal(["m-a", "m-b"], vm.Models);

        settings.Vault.Save(new ChatGptSession(
            "oaiapp_1", "sub-1", "a@example.com", "id-tok", "acc-tok", "ref-tok",
            _clock.Now.AddMinutes(1), ["openid", ChatGptSession.PlanScope])
        { GrantId = Guid.NewGuid() });
        vm.Service = TeacherService.ChatGpt;
        Assert.True(vm.SignedIn);

        await vm.RefreshModelsCommand.ExecuteAsync(null);

        Assert.False(vm.SignedIn);
        Assert.Equal("使用 ChatGPT 登录", vm.SignInLabel);
        Assert.Equal("还没登录 ChatGPT", vm.Account);
    }

    [Fact]
    public void BusyAndMismatchedServiceOrAddressLockForgetKeyAndPreserveCompatibleAddress()
    {
        var settings = new TeacherSettings(_dir.FullName);
        var vm = CreateVm(settings, _ => { }, _ => new HttpResponseMessage(HttpStatusCode.OK));

        vm.Service = TeacherService.Compatible;
        vm.Address = "https://custom.example.com/v1";
        vm.Model = "gpt-test";
        vm.Save("sk-secret");

        Assert.True(vm.HasKey);
        Assert.True(vm.ForgetKeyCommand.CanExecute(null));

        vm.Busy = true;
        Assert.False(vm.Idle);
        Assert.False(vm.ForgetKeyCommand.CanExecute(null));
        Assert.False(vm.RefreshModelsCommand.CanExecute(null));
        Assert.False(vm.SignInCommand.CanExecute(null));
        Assert.False(vm.SignOutCommand.CanExecute(null));
        vm.Busy = false;

        vm.Address = "https://other.example.com/v1";
        Assert.False(vm.HasKey);
        Assert.False(vm.ForgetKeyCommand.CanExecute(null));

        vm.Address = "https://custom.example.com/v1/";
        Assert.True(vm.HasKey);

        vm.Service = TeacherService.ChatGpt;
        Assert.False(vm.HasKey);
        vm.Model = "gpt-5";
        vm.Save("");

        var reloaded = new TeacherViewModel(TeacherSettings.Load(_dir.FullName));
        Assert.Equal(TeacherService.ChatGpt, reloaded.Service);
        Assert.Equal("https://custom.example.com/v1/", reloaded.Address);
    }

    [Fact]
    public async Task BusyGuardsSignInSignOutAndRefreshModels()
    {
        var settings = new TeacherSettings(_dir.FullName);
        var browserCalled = false;
        var httpCalled = false;
        var vm = CreateVm(
            settings,
            _ => browserCalled = true,
            _ =>
            {
                httpCalled = true;
                return new HttpResponseMessage(HttpStatusCode.OK);
            });
        var originalNote = vm.Note;
        vm.Busy = true;

        await vm.SignInCommand.ExecuteAsync(null);
        await vm.SignOutCommand.ExecuteAsync(null);
        await vm.RefreshModelsCommand.ExecuteAsync(null);

        Assert.True(vm.Busy);
        Assert.False(vm.WaitingForBrowser);
        Assert.False(browserCalled);
        Assert.False(httpCalled);
        Assert.Equal(originalNote, vm.Note);
    }

    private TeacherViewModel CreateVm(
        TeacherSettings settings,
        Action<Uri> openBrowser,
        Func<HttpRequestMessage, HttpResponseMessage> handler,
        TimeSpan? revokeTimeout = null) =>
        CreateVm(settings, openBrowser, (req, _) => Task.FromResult(handler(req)), revokeTimeout);

    private TeacherViewModel CreateVm(
        TeacherSettings settings,
        Action<Uri> openBrowser,
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> handler,
        TimeSpan? revokeTimeout = null) =>
        new(settings, () => new HttpClient(new StubHandler(handler)), openBrowser, _clock)
        {
            RevokeTimeout = revokeTimeout ?? TimeSpan.FromSeconds(5),
        };

    private static void TriggerCallback(Uri authorizeUrl, out string nonce, string querySuffix)
    {
        var q = HttpUtility.ParseQueryString(authorizeUrl.Query);
        var state = q["state"]!;
        var redirect = q["redirect_uri"]!;
        nonce = q["nonce"] ?? "";
        var callbackUri = $"{redirect}?state={Uri.EscapeDataString(state)}&{querySuffix}";
        _ = Task.Run(async () =>
        {
            using var client = new HttpClient();
            await client.GetAsync(callbackUri);
        });
    }

    private HttpResponseMessage HandleOAuth(HttpRequestMessage req, string clientId, Func<string> getNonce, string sub, string email)
    {
        var path = req.RequestUri!.AbsolutePath;
        if (path.EndsWith("/.well-known/jwks.json", StringComparison.Ordinal))
        {
            var p = _rsa.ExportParameters(false);
            var jwks = JsonSerializer.Serialize(new
            {
                keys = new[]
                {
                    new { kty = "RSA", use = "sig", alg = "RS256", kid = "k1", n = Base64Url(p.Modulus!), e = Base64Url(p.Exponent!) },
                },
            });
            return JsonResponse(HttpStatusCode.OK, jwks);
        }
        if (path.EndsWith("/oauth/token", StringComparison.Ordinal))
        {
            var idToken = SignIdToken(clientId, getNonce(), sub, email);
            var body = JsonSerializer.Serialize(new
            {
                token_type = "Bearer",
                access_token = "acc-token",
                refresh_token = "ref-token",
                id_token = idToken,
                expires_in = 3600,
                scope = ChatGptAuth.Scopes,
            });
            return JsonResponse(HttpStatusCode.OK, body);
        }
        return JsonResponse(HttpStatusCode.NotFound, "{}");
    }

    private string SignIdToken(string clientId, string nonce, string sub, string email)
    {
        var header = Base64Url(Encoding.UTF8.GetBytes("""{"alg":"RS256","typ":"JWT","kid":"k1"}"""));
        var exp = _clock.Now.AddHours(1).ToUnixTimeSeconds();
        var nbf = _clock.Now.AddMinutes(-1).ToUnixTimeSeconds();
        var payload = Base64Url(JsonSerializer.SerializeToUtf8Bytes(new
        {
            iss = ChatGptAuth.Issuer,
            aud = clientId,
            azp = clientId,
            sub,
            email,
            nonce,
            exp,
            nbf,
        }));
        var signingInput = Encoding.ASCII.GetBytes(header + "." + payload);
        var sig = Base64Url(_rsa.SignData(signingInput, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
        return $"{header}.{payload}.{sig}";
    }

    private static HttpResponseMessage JsonResponse(HttpStatusCode code, string json) =>
        new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private sealed class StubHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            respond(request, cancellationToken);
    }

    private sealed class ManualClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 11, 21, 6, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }
}
