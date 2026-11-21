using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Inkwell.Core.ChatGpt;
using Inkwell.Core.Teacher;

namespace Inkwell.Core.Tests;

public sealed class ChatGptTest : IDisposable
{
    private const string Issued = "oaiapp_test_client";
    private static readonly Uri Redirect = new("http://127.0.0.1:54321/auth/callback");
    private readonly RSA _key = RSA.Create(2048);
    private readonly FixedClock _clock = new(new DateTimeOffset(2026, 11, 7, 10, 0, 0, TimeSpan.Zero));

    public void Dispose() => _key.Dispose();

    [Fact]
    public void FirstSignInRegistersWithTheAppNameAndPkce()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "urn:uuid:11111111-2222-4333-8444-555555555555", null, "old-id-token", "a@b.c");
        var query = Query(attempt.AuthorizeUrl);

        Assert.Equal("https://auth.openai.com/api/accounts/authorize", attempt.AuthorizeUrl.GetLeftPart(UriPartial.Path));
        Assert.True(attempt.IsNewRegistration);
        Assert.Equal("dynamic_agent_client", query["client_id"]);
        Assert.Equal("code", query["response_type"]);
        Assert.Equal(Redirect.ToString(), query["redirect_uri"]);
        Assert.Equal("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", query["scope"]);
        Assert.Equal("https://api.openai.com/v1", query["resource"]);
        Assert.Equal("S256", query["code_challenge_method"]);
        Assert.Equal(ChatGptAuth.Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(attempt.Verifier))), query["code_challenge"]);
        Assert.Equal((attempt.State, attempt.Nonce), (query["state"], query["nonce"]));
        Assert.Equal("urn:uuid:11111111-2222-4333-8444-555555555555", query["ext_agent_host_id"]);
        Assert.Equal("Inkwell", query["agent_name_hint"]);
        Assert.False(query.ContainsKey("id_token_hint"));
        Assert.NotEqual(attempt.State, ChatGptAuth.Begin(Redirect, "h", null, null, null).State);
    }

    [Fact]
    public void LaterSignInsReuseTheIssuedClientWithHints()
    {
        var query = Query(ChatGptAuth.Begin(Redirect, "h", Issued, "old-id-token", "a@b.c").AuthorizeUrl);

        Assert.Equal(Issued, query["client_id"]);
        Assert.Equal(("old-id-token", "a@b.c"), (query["id_token_hint"], query["login_hint"]));
        Assert.False(query.ContainsKey("agent_name_hint"));
    }

    [Fact]
    public async Task FinishingVerifiesTheIdTokenAndKeepsTheIssuedClient()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var server = AuthServer(IdToken(attempt.Nonce));
        var auth = new ChatGptAuth(new HttpClient(server), _clock);

        var session = await auth.FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None);

        Assert.Equal((Issued, "user-123", "learner@example.com"), (session.ClientId, session.Subject, session.Email));
        Assert.Equal(("access-1", "refresh-1"), (session.AccessToken, session.RefreshToken));
        Assert.True(session.CanUsePlan);
        Assert.Equal(_clock.GetUtcNow().AddSeconds(3600), session.ExpiresAt);
        var form = server.Forms.Single(f => f.Url.EndsWith("/oauth/token"));
        Assert.Equal("authorization_code", form.Fields["grant_type"]);
        Assert.Equal(Issued, form.Fields["client_id"]);
        Assert.Equal(("the-code", attempt.Verifier), (form.Fields["code"], form.Fields["code_verifier"]));
        Assert.Equal((Redirect.ToString(), "https://api.openai.com/v1"), (form.Fields["redirect_uri"], form.Fields["resource"]));
        Assert.DoesNotContain("access-1", session.ToString());
    }

    [Fact]
    public async Task ABadStateOrDeniedConsentStopsBeforeAnyTokenRequest()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var server = AuthServer(IdToken(attempt.Nonce));
        var auth = new ChatGptAuth(new HttpClient(server), _clock);

        await Assert.ThrowsAsync<ChatGptException>(() => auth.FinishAsync(attempt,
            new Dictionary<string, string> { ["state"] = "forged", ["code"] = "c", ["client_id"] = Issued }, CancellationToken.None));
        var denied = await Assert.ThrowsAsync<ChatGptException>(() => auth.FinishAsync(attempt,
            new Dictionary<string, string> { ["state"] = attempt.State, ["error"] = "access_denied" }, CancellationToken.None));
        Assert.Equal("你在 ChatGPT 页面上没有同意授权，没有登录。", denied.Message);
        await Assert.ThrowsAsync<ChatGptException>(() => auth.FinishAsync(attempt,
            new Dictionary<string, string> { ["state"] = attempt.State, ["code"] = "c" }, CancellationToken.None));
        Assert.Empty(server.Forms);
    }

    [Fact]
    public async Task AReauthorizationThatReturnsAnotherClientIsRejected()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", Issued, null, null);
        var server = AuthServer(IdToken(attempt.Nonce));

        await Assert.ThrowsAsync<ChatGptException>(() => new ChatGptAuth(new HttpClient(server), _clock)
            .FinishAsync(attempt, Callback(attempt, "oaiapp_someone_else"), CancellationToken.None));
        Assert.Empty(server.Forms);
    }

    [Theory]
    [InlineData("wrong-nonce", Issued, "https://auth.openai.com", 3600)]
    [InlineData(null, "oaiapp_other", "https://auth.openai.com", 3600)]
    [InlineData(null, Issued, "https://evil.example", 3600)]
    [InlineData(null, Issued, "https://auth.openai.com", -60)]
    public async Task IdTokensThatDoNotMatchAreRejected(string? nonce, string audience, string issuer, int lifetime)
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var server = AuthServer(IdToken(nonce ?? attempt.Nonce, audience, issuer, lifetime));

        var error = await Assert.ThrowsAsync<ChatGptException>(() => new ChatGptAuth(new HttpClient(server), _clock)
            .FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None));
        Assert.Equal("ChatGPT 返回的身份信息没有通过校验，为安全起见没有登录。", error.Message);
    }

    [Fact]
    public async Task AnIdTokenSignedByAnotherKeyIsRejected()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        using var other = RSA.Create(2048);
        var server = AuthServer(IdToken(attempt.Nonce, signer: other));

        await Assert.ThrowsAsync<ChatGptException>(() => new ChatGptAuth(new HttpClient(server), _clock)
            .FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None));
    }

    [Fact]
    public async Task RefreshRotatesTheRefreshTokenAndExpiredGrantsAskForSignIn()
    {
        var server = new FakeAuth(form => Json(new { access_token = "access-2", refresh_token = "refresh-2", expires_in = 3600, scope = "openid chatgpt.tokens.use.direct" }), _key);
        var auth = new ChatGptAuth(new HttpClient(server), _clock);

        var renewed = await auth.RefreshAsync(Session(), CancellationToken.None);

        Assert.Equal(("access-2", "refresh-2"), (renewed.AccessToken, renewed.RefreshToken));
        var form = Assert.Single(server.Forms).Fields;
        Assert.Equal(("refresh_token", Issued, "refresh-1"), (form["grant_type"], form["client_id"], form["refresh_token"]));
        Assert.False(form.ContainsKey("scope"));

        var expired = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.BadRequest) { Content = new StringContent("{\"error\":\"refresh_token_reused\"}") }, _key);
        var error = await Assert.ThrowsAsync<ChatGptException>(() => new ChatGptAuth(new HttpClient(expired), _clock).RefreshAsync(Session(), CancellationToken.None));
        Assert.True(error.SignInAgain);
    }

    [Fact]
    public async Task RevokeSendsTheRefreshToken()
    {
        var server = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK), _key);

        Assert.True(await new ChatGptAuth(new HttpClient(server), _clock).RevokeAsync(Session(), CancellationToken.None));

        var form = Assert.Single(server.Forms);
        Assert.EndsWith("/oauth/revoke", form.Url);
        Assert.Equal(("refresh-1", "refresh_token", Issued), (form.Fields["token"], form.Fields["token_type_hint"], form.Fields["client_id"]));
    }

    [Fact]
    public async Task TheTeacherStreamsAResponseWithStoreOffAndRefreshesFirst()
    {
        var vault = new MemoryVault { Session = Session() with { ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };
        var api = new FakeAuth(form => Json(new { access_token = "access-2", refresh_token = "refresh-2", expires_in = 3600, scope = "chatgpt.tokens.use.direct" }), _key)
        {
            Api = request => Sse(Event("response.created"), Event("response.output_text.delta", delta: "{\"band\":"), Event("response.output_text.delta", delta: "11}"), Event("response.completed")),
        };
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), vault, _clock, "gpt-test");

        var reply = await teacher.AskAsync("instruction", "material", jsonReply: true, CancellationToken.None);

        Assert.Equal("{\"band\":11}", reply);
        Assert.Equal("refresh-2", vault.Session!.RefreshToken);
        var call = api.Calls.Single(c => c.Url.EndsWith("/v1/responses"));
        Assert.Equal("Bearer access-2", call.Authorization);
        using var body = JsonDocument.Parse(call.Body);
        Assert.False(body.RootElement.GetProperty("store").GetBoolean());
        Assert.True(body.RootElement.GetProperty("stream").GetBoolean());
        Assert.Equal("instruction", body.RootElement.GetProperty("instructions").GetString());
        Assert.Equal("gpt-test", body.RootElement.GetProperty("model").GetString());
        Assert.Equal("material", body.RootElement.GetProperty("input")[0].GetProperty("content").GetString());
        Assert.False(body.RootElement.TryGetProperty("reasoning", out _));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task PendingRenewalLeavesNewerLocalSessionStateIntact(bool signInAgain, bool refreshFails)
    {
        var expired = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) };
        var replacement = signInAgain ? Session() with { GrantId = Guid.NewGuid() } : null;
        var vault = new MemoryVault { Session = expired };
        var api = new FakeAuth(_ =>
        {
            vault.Session = replacement;
            return refreshFails
                ? new HttpResponseMessage(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_grant"}""") }
                : Json(new { access_token = "new-access", refresh_token = "new-refresh", expires_in = 3600, scope = ChatGptSession.PlanScope });
        }, _key);
        using var http = new HttpClient(api);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "gpt-test");

        await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Same(replacement, vault.Session);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task SigningOutDuringRenewalStopsTheCallAndReturnsTheRotatedGrant()
    {
        var expired = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) };
        var vault = new MemoryVault { Session = expired, RememberedIdToken = "fixture-hint" };
        Task<ChatGptSession?>? ending = null;
        var api = new FakeAuth(_ =>
        {
            ending = ChatGptTeacher.EndSessionAsync(vault, CancellationToken.None);
            return Json(new { access_token = "renewed-access", refresh_token = "renewed-refresh", expires_in = 3600, scope = ChatGptSession.PlanScope });
        }, _key);
        using var http = new HttpClient(api);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "gpt-test");

        await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.NotNull(ending);
        Assert.Equal("renewed-refresh", (await ending)?.RefreshToken);
        Assert.Null(vault.Session);
        Assert.Null(vault.RememberedIdToken);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task AChosenDepthIsSentAsReasoningEffort()
    {
        var api = new FakeAuth(_ => throw new InvalidOperationException("no refresh expected"), _key)
        {
            Api = _ => Sse(Event("response.output_text.delta", delta: "{}"), Event("response.completed")),
        };
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock),
            new MemoryVault { Session = Session() }, _clock, "m", Depth.High);

        await teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None);

        using var body = JsonDocument.Parse(api.Calls.Single(c => c.Url.EndsWith("/v1/responses")).Body);
        Assert.Equal("high", body.RootElement.GetProperty("reasoning").GetProperty("effort").GetString());
    }

    [Fact]
    public async Task OnlyACompletedResponseCounts()
    {
        Assert.StartsWith("和 ChatGPT 的连接中途断了", (await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader(Event("response.output_text.delta", delta: "{")), CancellationToken.None))).Message);
        Assert.StartsWith("ChatGPT 没写完就停了", (await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader(Event("response.incomplete")), CancellationToken.None))).Message);
        var failed = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n";
        Assert.StartsWith("ChatGPT 套餐额度这段时间用完了", (await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader(failed), CancellationToken.None))).Message);
    }

    [Fact]
    public async Task WithoutPlanConsentOrSessionNothingIsSent()
    {
        var api = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK), _key);
        var noPlan = new MemoryVault { Session = Session() with { Scopes = ["openid", "email"] } };

        var error = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), noPlan, _clock, "m").AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.True(error.SignInAgain);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task ARejectedLoginAsksForSignInAgain()
    {
        var api = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK), _key)
        {
            Api = _ => new HttpResponseMessage(HttpStatusCode.Unauthorized)
            {
                Content = new StringContent("{\"error\":{\"code\":\"subscription_sharing_invalid_user\"}}"),
            },
        };
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), new MemoryVault { Session = Session() }, _clock, "m");

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.True(error.SignInAgain);
    }

    [Fact]
    public async Task ModelsKeepListedOnesInServerOrder()
    {
        var api = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK), _key)
        {
            Api = _ => Json(new
            {
                models = new object[]
            {
                new { slug = "gpt-b", display_name = "B", visibility = "list" },
                new { slug = "gpt-hidden", display_name = "H", visibility = "hide" },
                new { slug = "gpt-a", display_name = "", visibility = "list" },
            }
            }),
        };
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), new MemoryVault { Session = Session() }, _clock, "m");

        Assert.Equal([new PlanModel("gpt-b", "B"), new PlanModel("gpt-a", "gpt-a")], await teacher.ModelsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task TheLoopbackAnswersOnlyTheCallbackPath()
    {
        using var loopback = new LoopbackCallback();
        Assert.Equal("127.0.0.1", loopback.RedirectUri.Host);
        Assert.Equal("/auth/callback", loopback.RedirectUri.AbsolutePath);
        var waiting = loopback.WaitAsync("s", new CancellationTokenSource(TimeSpan.FromSeconds(10)).Token);

        using var http = new HttpClient();
        var stray = await http.GetAsync(new Uri(loopback.RedirectUri, "/favicon.ico"));
        var forged = await http.GetAsync(loopback.RedirectUri + "?code=evil&state=other");
        var answer = await http.GetAsync(loopback.RedirectUri + "?code=abc%2B1&state=s&client_id=oaiapp_x");

        Assert.Equal(HttpStatusCode.NotFound, stray.StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, forged.StatusCode);
        Assert.Equal(HttpStatusCode.OK, answer.StatusCode);
        var query = await waiting;
        Assert.Equal(("abc+1", "s", "oaiapp_x"), (query["code"], query["state"], query["client_id"]));
    }

    [Fact]
    public async Task AConnectionDroppedMidStreamIsAnErrorNotACrash()
    {
        var api = new FakeAuth(_ => Json(new { }), _key)
        {
            Api = _ => new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StreamContent(new BrokenStream(Encoding.UTF8.GetBytes(Event("response.output_text.delta", delta: "{\"band\":")))),
            },
        };
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), new MemoryVault { Session = Session() }, _clock, "m");

        await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));
    }

    [Fact]
    public async Task ARefreshAnsweredWithSomethingOtherThanJsonIsAnError()
    {
        var vault = new MemoryVault { Session = Session() with { ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };
        var api = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<html>proxy</html>") }, _key);
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), vault, _clock, "m");

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.False(error.SignInAgain);
        Assert.Equal("refresh-1", vault.Session!.RefreshToken);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task AResetStrayConnectionDoesNotEndTheLoopbackWait()
    {
        using var loopback = new LoopbackCallback();
        var waiting = loopback.WaitAsync("s", new CancellationTokenSource(TimeSpan.FromSeconds(10)).Token);

        using (var stray = new TcpClient())
        {
            await stray.ConnectAsync(IPAddress.Loopback, loopback.RedirectUri.Port);
            stray.LingerState = new LingerOption(true, 0);
        }
        using var http = new HttpClient();
        var answer = await http.GetAsync(loopback.RedirectUri + "?code=c&state=s");

        Assert.Equal(HttpStatusCode.OK, answer.StatusCode);
        Assert.Equal("c", (await waiting)["code"]);
    }

    [Fact]
    public async Task AFailedEventWithoutAResponseObjectIsStillAnError() =>
        await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader("data: {\"type\":\"response.failed\",\"response\":null}\n\n"), CancellationToken.None));

    [Fact]
    public async Task ARenewalThatCannotBeStoredSendsNothing()
    {
        var vault = new MemoryVault { Session = Session() with { ExpiresAt = _clock.GetUtcNow().AddMinutes(2) }, FailSave = true };
        var api = new FakeAuth(_ => Json(new { access_token = "access-2", refresh_token = "refresh-2", expires_in = 3600, scope = "chatgpt.tokens.use.direct" }), _key);
        var teacher = new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), vault, _clock, "m");

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Contains("没能存到", error.Message);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task AStreamThatGoesSilentStopsWithAnError()
    {
        var error = await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new SilentReader(), CancellationToken.None, TimeSpan.FromMilliseconds(50)));

        Assert.Contains("太久没有动静", error.Message);
    }

    [Fact]
    public async Task StoppingASilentStreamIsStillAStop()
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            ChatGptTeacher.ReadStream(new SilentReader(), stop.Token, TimeSpan.FromMinutes(5)));
    }

    [Fact]
    public async Task RefreshOmittingOrBlankingScopePreservesPreviousScopesAndGrantId()
    {
        var original = Session() with { GrantId = Guid.NewGuid() };
        var omittedScope = new FakeAuth(_ => Json(new { access_token = "access-2", refresh_token = "refresh-2", expires_in = 3600 }), _key);
        var blankScope = new FakeAuth(_ => Json(new { access_token = "access-3", refresh_token = "", expires_in = 3600, scope = "" }), _key);

        var renewed1 = await new ChatGptAuth(new HttpClient(omittedScope), _clock).RefreshAsync(original, CancellationToken.None);
        var renewed2 = await new ChatGptAuth(new HttpClient(blankScope), _clock).RefreshAsync(renewed1, CancellationToken.None);

        Assert.Equal(original.GrantId, renewed1.GrantId);
        Assert.Equal(original.Scopes, renewed1.Scopes);
        Assert.True(renewed1.CanUsePlan);
        Assert.Equal("refresh-2", renewed2.RefreshToken);
        Assert.Equal(original.Scopes, renewed2.Scopes);
    }

    [Fact]
    public async Task RefreshDowngradingScopesClearsMatchingVaultWithoutSaving()
    {
        var expired = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) };
        var vault = new MemoryVault { Session = expired };
        var server = new FakeAuth(_ => Json(new { access_token = "a2", refresh_token = "r2", expires_in = 3600, scope = "openid email" }), _key);
        using var http = new HttpClient(server);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "m");

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.True(error.SignInAgain);
        Assert.Null(vault.Session);
        Assert.Empty(server.Calls);
    }

    [Fact]
    public async Task InvalidClientSetsForgetClientOnRefreshAndReauthOnly()
    {
        var invalidClientBody = () => new HttpResponseMessage(HttpStatusCode.BadRequest)
        {
            Content = new StringContent("""{"error":"invalid_client"}"""),
        };

        var refreshErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(new FakeAuth(_ => invalidClientBody(), _key)), _clock)
                .RefreshAsync(Session(), CancellationToken.None));
        Assert.True(refreshErr.SignInAgain);
        Assert.True(refreshErr.ForgetClient);

        var reauthAttempt = ChatGptAuth.Begin(Redirect, "h", Issued, null, null);
        var reauthTokenErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(new FakeAuth(_ => invalidClientBody(), _key)), _clock)
                .FinishAsync(reauthAttempt, Callback(reauthAttempt, Issued), CancellationToken.None));
        Assert.True(reauthTokenErr.ForgetClient);

        var reauthCallbackErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(new FakeAuth(_ => invalidClientBody(), _key)), _clock)
                .FinishAsync(reauthAttempt, new Dictionary<string, string> { ["state"] = reauthAttempt.State, ["error"] = "invalid_client" }, CancellationToken.None));
        Assert.True(reauthCallbackErr.ForgetClient);

        var freshAttempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var freshErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(new FakeAuth(_ => invalidClientBody(), _key)), _clock)
                .FinishAsync(freshAttempt, Callback(freshAttempt, Issued), CancellationToken.None));
        Assert.False(freshErr.ForgetClient);
    }

    [Fact]
    public async Task FreshSessionClearsRememberedClientOnInvalidClientOnlyWhenGrantMatches()
    {
        var expired = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) };
        var vault = new MemoryVault { Session = expired, RememberedClientId = Issued, RememberedIdToken = "hint" };
        var server = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.BadRequest)
        {
            Content = new StringContent("""{"error":"invalid_client"}"""),
        }, _key);
        using var http = new HttpClient(server);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "m");

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.True(error.ForgetClient);
        Assert.Null(vault.Session);
        Assert.Null(vault.RememberedClientId);
        Assert.Null(vault.RememberedIdToken);

        var replacement = Session() with { GrantId = Guid.NewGuid() };
        var vault2 = new MemoryVault { Session = expired, RememberedClientId = Issued, RememberedIdToken = "newer-hint" };
        var raceServer = new FakeAuth(_ =>
        {
            vault2.Session = replacement;
            return new HttpResponseMessage(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_client"}""") };
        }, _key);
        using var http2 = new HttpClient(raceServer);
        var teacher2 = new ChatGptTeacher(http2, new ChatGptAuth(http2, _clock), vault2, _clock, "m");

        await Assert.ThrowsAsync<ChatGptException>(() => teacher2.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Same(replacement, vault2.Session);
        Assert.Equal(Issued, vault2.RememberedClientId);
        Assert.Equal("newer-hint", vault2.RememberedIdToken);
    }

    [Fact]
    public async Task ExpiresInHandlesInvalidNegativeAndHugeValuesWithoutOverflow()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var idToken = IdToken(attempt.Nonce);
        var server = new FakeAuth(_ => Json(new
        {
            access_token = "a1",
            refresh_token = "r1",
            id_token = idToken,
            token_type = "bearer",
            expires_in = -5,
            scope = ChatGptSession.PlanScope,
        }), _key);
        var session = await new ChatGptAuth(new HttpClient(server), _clock)
            .FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None);
        Assert.NotEqual(Guid.Empty, session.GrantId);
        Assert.Equal(_clock.GetUtcNow().AddSeconds(3600), session.ExpiresAt);

        var nearMaxClock = new FixedClock(DateTimeOffset.MaxValue.AddDays(-1));
        var hugeServer = new FakeAuth(_ => Json(new
        {
            access_token = "a2",
            refresh_token = "r2",
            expires_in = 999999999999999999L,
            scope = ChatGptSession.PlanScope,
        }), _key);
        var renewed = await new ChatGptAuth(new HttpClient(hugeServer), nearMaxClock).RefreshAsync(session, CancellationToken.None);
        Assert.Equal(DateTimeOffset.MaxValue, renewed.ExpiresAt);
    }

    [Fact]
    public async Task RefreshVerifiesNewIdTokenAndRejectsMismatchedSubject()
    {
        var freshId = IdToken(nonce: "ignored-on-refresh");
        var goodServer = new FakeAuth(_ => Json(new { access_token = "a2", id_token = freshId }), _key);
        var renewed = await new ChatGptAuth(new HttpClient(goodServer), _clock).RefreshAsync(Session(), CancellationToken.None);
        Assert.Equal(freshId, renewed.IdToken);

        var emptyServer = new FakeAuth(_ => Json(new { access_token = "a2", id_token = "" }), _key);
        var kept = await new ChatGptAuth(new HttpClient(emptyServer), _clock).RefreshAsync(Session(), CancellationToken.None);
        Assert.Equal("id", kept.IdToken);

        var wrongSubHeader = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { alg = "RS256", kid = "test-key" }));
        var wrongSubPayload = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new
        {
            iss = ChatGptAuth.Issuer,
            aud = Issued,
            sub = "other-user",
            exp = _clock.GetUtcNow().AddHours(1).ToUnixTimeSeconds(),
        }));
        var wrongSubSig = ChatGptAuth.Base64Url(_key.SignData(
            Encoding.ASCII.GetBytes(wrongSubHeader + "." + wrongSubPayload), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
        var wrongSubToken = wrongSubHeader + "." + wrongSubPayload + "." + wrongSubSig;
        var badServer = new FakeAuth(_ => Json(new { access_token = "a2", id_token = wrongSubToken }), _key);
        await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(badServer), _clock).RefreshAsync(Session(), CancellationToken.None));
    }

    [Fact]
    public async Task OidcClaimsTokenTypeAndAccessTokenValidationAreEnforced()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        string SignCustom(object headerObj, object payloadObj)
        {
            var h = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(headerObj));
            var p = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(payloadObj));
            var s = ChatGptAuth.Base64Url(_key.SignData(Encoding.ASCII.GetBytes(h + "." + p), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
            return $"{h}.{p}.{s}";
        }

        var now = _clock.GetUtcNow().ToUnixTimeSeconds();
        string[] badTokens =
        [
            SignCustom(new { alg = "RS256", kid = "" }, new { iss = ChatGptAuth.Issuer, aud = Issued, sub = "u", nonce = attempt.Nonce, exp = now + 300 }),
            SignCustom(new { alg = "RS256", kid = "test-key" }, new { iss = ChatGptAuth.Issuer, aud = Issued, azp = "other", sub = "u", nonce = attempt.Nonce, exp = now + 300 }),
            SignCustom(new { alg = "RS256", kid = "test-key" }, new { iss = ChatGptAuth.Issuer, aud = Issued, sub = "u", nonce = attempt.Nonce, nbf = now + 60, exp = now + 300 }),
            SignCustom(new { alg = "RS256", kid = "test-key" }, new { iss = ChatGptAuth.Issuer, aud = Issued, sub = "u", nonce = attempt.Nonce, exp = 999999999999999999L }),
        ];

        foreach (var token in badTokens)
        {
            var auth = new ChatGptAuth(new HttpClient(AuthServer(token)), _clock);
            await Assert.ThrowsAsync<ChatGptException>(() => auth.FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None));
        }

        var validId = IdToken(attempt.Nonce);
        var noTypeServer = new FakeAuth(_ => Json(new { access_token = "a1", refresh_token = "r1", id_token = validId }), _key);
        await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(noTypeServer), _clock).FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None));

        var badAccessServer = new FakeAuth(_ => Json(new { access_token = "a1\nbad", refresh_token = "r1", id_token = validId, token_type = "Bearer" }), _key);
        await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(badAccessServer), _clock).FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None));
    }

    [Fact]
    public async Task JwksSkipsMalformedOrWrongUseKeysAndHandlesBadCharsetAndSizeCaps()
    {
        var attempt = ChatGptAuth.Begin(Redirect, "h", null, null, null);
        var idToken = IdToken(attempt.Nonce);
        var p = _key.ExportParameters(false);
        var jwksJson = JsonSerializer.Serialize(new
        {
            keys = new object[]
            {
                new { kty = "RSA", kid = "test-key", use = "enc", alg = "RS256", n = ChatGptAuth.Base64Url(p.Modulus!), e = ChatGptAuth.Base64Url(p.Exponent!) },
                new { kty = "RSA", kid = "test-key", use = "sig", alg = "RS256", n = "???", e = "AQAB" },
                new { kty = "RSA", kid = "test-key", use = "sig", alg = "RS256", n = ChatGptAuth.Base64Url(p.Modulus!), e = ChatGptAuth.Base64Url(p.Exponent!) },
            },
        });
        var handler = new ScriptedHandler(req =>
        {
            if (req.RequestUri!.ToString() == "https://auth.openai.com/.well-known/jwks.json")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(Encoding.UTF8.GetBytes(jwksJson))
                    {
                        Headers = { { "Content-Type", "application/json; charset=bogus-charset" } },
                    },
                });
            }
            return Task.FromResult(Json(new
            {
                access_token = "access-1",
                refresh_token = "refresh-1",
                id_token = idToken,
                token_type = "Bearer",
                expires_in = 3600,
                scope = ChatGptSession.PlanScope,
            }));
        });

        var session = await new ChatGptAuth(new HttpClient(handler), _clock)
            .FinishAsync(attempt, Callback(attempt, Issued), CancellationToken.None);
        Assert.Equal("user-123", session.Subject);

        var oversizedHandler = new ScriptedHandler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(new byte[1024 * 1024 + 1]),
        }));
        var sizeError = await Assert.ThrowsAsync<ChatGptException>(() =>
            new ChatGptAuth(new HttpClient(oversizedHandler), _clock).RefreshAsync(Session(), CancellationToken.None));
        Assert.Contains("太大", sizeError.Message);
    }

    [Fact]
    public async Task LoopbackHandlesSilentPreconnectBadHostDuplicateQueryAndCancellation()
    {
        using var loopback = new LoopbackCallback();
        using var testTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var waiting = loopback.WaitAsync("good-state", testTimeout.Token);

        using var silent = new TcpClient();
        await silent.ConnectAsync(IPAddress.Loopback, loopback.RedirectUri.Port, testTimeout.Token);

        using var badHostClient = new TcpClient();
        await badHostClient.ConnectAsync(IPAddress.Loopback, loopback.RedirectUri.Port, testTimeout.Token);
        await using (var s = badHostClient.GetStream())
        {
            await s.WriteAsync(Encoding.ASCII.GetBytes("GET /auth/callback?code=c&state=good-state HTTP/1.1\r\nHost: evil.example\r\n\r\n"), testTimeout.Token);
            var buf = new byte[512];
            var read = await s.ReadAsync(buf, testTimeout.Token);
            Assert.StartsWith("HTTP/1.1 400 Bad Request", Encoding.ASCII.GetString(buf, 0, read));
        }

        using var badPctClient = new TcpClient();
        await badPctClient.ConnectAsync(IPAddress.Loopback, loopback.RedirectUri.Port, testTimeout.Token);
        await using (var s = badPctClient.GetStream())
        {
            await s.WriteAsync(Encoding.ASCII.GetBytes(
                $"GET /auth/callback?code=%ZZ&state=good-state HTTP/1.1\r\nHost: 127.0.0.1:{loopback.RedirectUri.Port}\r\n\r\n"), testTimeout.Token);
            var buf = new byte[512];
            var read = await s.ReadAsync(buf, testTimeout.Token);
            Assert.StartsWith("HTTP/1.1 400 Bad Request", Encoding.ASCII.GetString(buf, 0, read));
        }

        using var http = new HttpClient();
        using var dup = await http.GetAsync(loopback.RedirectUri + "?code=1&code=2&state=good-state", testTimeout.Token);
        Assert.Equal(HttpStatusCode.BadRequest, dup.StatusCode);

        using var ok = await http.GetAsync(loopback.RedirectUri + "?code=ok-code&state=good-state", testTimeout.Token);
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
        Assert.Equal("ok-code", (await waiting)["code"]);

        using var cancelCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(30));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => loopback.WaitAsync("s", cancelCts.Token));
    }

    [Fact]
    public async Task PerVaultRefreshLockIsolatesVaultsAndRespectsInternalTimeout()
    {
        var enteredA = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseA = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var vaultA = new MemoryVault { Session = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };
        var vaultB = new MemoryVault { Session = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };

        var handlerA = new ScriptedHandler(async _ =>
        {
            enteredA.TrySetResult();
            await releaseA.Task;
            return Json(new { access_token = "a-new", refresh_token = "r-new", expires_in = 3600, scope = ChatGptSession.PlanScope });
        });
        var handlerB = new FakeAuth(_ => Json(new { access_token = "b-new", refresh_token = "r-b", expires_in = 3600, scope = ChatGptSession.PlanScope }), _key)
        {
            Api = _ => Sse(Event("response.output_text.delta", delta: "ok"), Event("response.completed")),
        };

        using var httpA = new HttpClient(handlerA);
        using var httpB = new HttpClient(handlerB);
        var teacherA = new ChatGptTeacher(httpA, new ChatGptAuth(httpA, _clock), vaultA, _clock, "m");
        var teacherB = new ChatGptTeacher(httpB, new ChatGptAuth(httpB, _clock), vaultB, _clock, "m");

        var taskA = teacherA.AskAsync("i", "m", jsonReply: false, CancellationToken.None);
        await enteredA.Task;
        Assert.Equal("ok", await teacherB.AskAsync("i", "m", jsonReply: false, CancellationToken.None));
        releaseA.SetResult();
        await Assert.ThrowsAsync<ChatGptException>(() => taskA);

        var vaultTimeout = new MemoryVault { Session = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };
        var hungHandler = new ScriptedHandler(async (_, ct) =>
        {
            await Task.Delay(Timeout.Infinite, ct);
            return new HttpResponseMessage(HttpStatusCode.OK);
        });
        using var hungHttp = new HttpClient(hungHandler);
        var timeoutTeacher = new ChatGptTeacher(hungHttp, new ChatGptAuth(hungHttp, _clock), vaultTimeout, TimeProvider.System, "m")
        {
            RefreshLimit = TimeSpan.FromMilliseconds(40),
        };
        var timeoutErr = await Assert.ThrowsAsync<ChatGptException>(() => timeoutTeacher.AskAsync("i", "m", jsonReply: false, CancellationToken.None));
        Assert.False(timeoutErr.SignInAgain);
        Assert.Equal("refresh-1", vaultTimeout.Session!.RefreshToken);
    }

    [Fact]
    public async Task CallerCancelDuringRefreshStillSavesRotatedTokenAndSendDoesNotHoldVaultLock()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var vault = new MemoryVault { Session = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) } };
        var handler = new ScriptedHandler(async _ =>
        {
            entered.TrySetResult();
            await release.Task;
            return Json(new { access_token = "rotated-access", refresh_token = "rotated-refresh", expires_in = 3600, scope = ChatGptSession.PlanScope });
        });
        using var http = new HttpClient(handler);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "m");
        using var cancelCts = new CancellationTokenSource();

        var ask = teacher.AskAsync("i", "m", jsonReply: false, cancelCts.Token);
        await entered.Task;
        cancelCts.Cancel();
        release.SetResult();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => ask);
        Assert.Equal("rotated-refresh", vault.Session!.RefreshToken);

        var sendVault = new MemoryVault { Session = Session() with { GrantId = Guid.NewGuid() } };
        var lockCheckHandler = new ScriptedHandler(async _ =>
        {
            var loaded = await Task.Run(() =>
            {
                lock (sendVault) return sendVault.Load();
            });
            Assert.NotNull(loaded);
            return Sse(Event("response.output_text.delta", delta: " ok "), Event("response.completed"));
        });
        using var lockHttp = new HttpClient(lockCheckHandler);
        var sendTeacher = new ChatGptTeacher(lockHttp, new ChatGptAuth(lockHttp, _clock), sendVault, _clock, "m");
        Assert.Equal(" ok ", await sendTeacher.AskAsync("i", "m", jsonReply: false, CancellationToken.None));
    }

    [Fact]
    public async Task ModelsAndErrorBodyHandleSizeCapTimeoutAndBadCharset()
    {
        var badCharsetModels = new ScriptedHandler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(Encoding.UTF8.GetBytes("""{"models":[{"slug":"gpt-1","display_name":"GPT 1","visibility":"list"}]}"""))
            {
                Headers = { { "Content-Type", "application/json; charset=bogus-charset" } },
            },
        }));
        using var http1 = new HttpClient(badCharsetModels);
        var teacher1 = new ChatGptTeacher(http1, new ChatGptAuth(http1, _clock), new MemoryVault { Session = Session() }, _clock, "m");
        Assert.Equal([new PlanModel("gpt-1", "GPT 1")], await teacher1.ModelsAsync(CancellationToken.None));

        var oversizedModels = new ScriptedHandler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(new byte[2 * 1024 * 1024 + 1]),
        }));
        using var http2 = new HttpClient(oversizedModels);
        var teacher2 = new ChatGptTeacher(http2, new ChatGptAuth(http2, _clock), new MemoryVault { Session = Session() }, _clock, "m");
        var modelsErr = await Assert.ThrowsAsync<ChatGptException>(() => teacher2.ModelsAsync(CancellationToken.None));
        Assert.Contains("太大", modelsErr.Message);

        var badCharsetError = new ScriptedHandler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.Unauthorized)
        {
            Content = new ByteArrayContent(Encoding.UTF8.GetBytes("""{"error":{"code":"subscription_sharing_invalid_user"}}"""))
            {
                Headers = { { "Content-Type", "application/json; charset=bogus-charset" } },
            },
        }));
        using var http3 = new HttpClient(badCharsetError);
        var teacher3 = new ChatGptTeacher(http3, new ChatGptAuth(http3, _clock), new MemoryVault { Session = Session() }, _clock, "m");
        var err3 = await Assert.ThrowsAsync<ChatGptException>(() => teacher3.AskAsync("i", "m", jsonReply: false, CancellationToken.None));
        Assert.True(err3.SignInAgain);

        var slowError = new ScriptedHandler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.TooManyRequests)
        {
            Content = new StreamContent(new HangingStream()),
        }));
        using var http4 = new HttpClient(slowError);
        var teacher4 = new ChatGptTeacher(http4, new ChatGptAuth(http4, _clock), new MemoryVault { Session = Session() }, _clock, "m")
        {
            ErrorBodyLimit = TimeSpan.FromMilliseconds(30),
        };
        var err4 = await Assert.ThrowsAsync<ChatGptException>(() => teacher4.AskAsync("i", "m", jsonReply: false, CancellationToken.None));
        Assert.Contains("太频繁", err4.Message);
    }

    [Fact]
    public async Task ReadStreamSupportsCompletedFallbackRefusalTotalTimeoutAndSizeCap()
    {
        var completedFallback =
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"  kept whitespace  \"}]}]}}\n\n";
        Assert.Equal("  kept whitespace  ", await ChatGptTeacher.ReadStream(new StringReader(completedFallback), CancellationToken.None));

        var refusalEvent = "data: {\"type\":\"response.refusal.done\"}\n\n";
        var refusalErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader(refusalEvent), CancellationToken.None));
        Assert.Contains("拒绝回答", refusalErr.Message);

        var completedRefusal =
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}}\n\n";
        var completedRefusalErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader(completedRefusal), CancellationToken.None));
        Assert.Contains("拒绝回答", completedRefusalErr.Message);

        var totalErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new SilentReader(), CancellationToken.None, TimeSpan.FromMinutes(1), TimeSpan.FromMilliseconds(40)));
        Assert.Contains("批改时间太长", totalErr.Message);

        var oversizedErr = await Assert.ThrowsAsync<ChatGptException>(() =>
            ChatGptTeacher.ReadStream(new StringReader("data: {\"type\":\"response.created\"}\n\n"), CancellationToken.None, maxChars: 10));
        Assert.Contains("超过大小限制", oversizedErr.Message);
    }

    [Fact]
    public async Task RefreshWithWhitespaceScopeAndRefreshTokenPreservesPreviousValuesAndVault()
    {
        var original = Session() with { GrantId = Guid.NewGuid() };
        var wsAuth = new FakeAuth(_ => Json(new
        {
            access_token = "access-ws",
            refresh_token = "   ",
            expires_in = 3600,
            scope = "   ",
        }), _key);
        var renewed = await new ChatGptAuth(new HttpClient(wsAuth), _clock).RefreshAsync(original, CancellationToken.None);

        Assert.Equal(original.GrantId, renewed.GrantId);
        Assert.Equal(original.RefreshToken, renewed.RefreshToken);
        Assert.Equal(original.Scopes, renewed.Scopes);
        Assert.True(renewed.CanUsePlan);

        var paddedRefreshAuth = new FakeAuth(_ => Json(new
        {
            access_token = "access-padded",
            refresh_token = "  padded-refresh  ",
            expires_in = 3600,
            scope = ChatGptSession.PlanScope,
        }), _key);
        var padded = await new ChatGptAuth(new HttpClient(paddedRefreshAuth), _clock).RefreshAsync(original, CancellationToken.None);
        Assert.Equal("  padded-refresh  ", padded.RefreshToken);

        var expiring = Session() with { GrantId = Guid.NewGuid(), ExpiresAt = _clock.GetUtcNow().AddMinutes(2) };
        var vault = new MemoryVault { Session = expiring };
        var server = new FakeAuth(_ => Json(new
        {
            access_token = "access-teacher-ws",
            refresh_token = "   ",
            expires_in = 3600,
            scope = "   ",
        }), _key)
        {
            Api = _ => Sse(Event("response.output_text.delta", delta: "ok"), Event("response.completed")),
        };
        using var http = new HttpClient(server);
        var teacher = new ChatGptTeacher(http, new ChatGptAuth(http, _clock), vault, _clock, "m");

        var reply = await teacher.AskAsync("i", "m", jsonReply: false, CancellationToken.None);

        Assert.Equal("ok", reply);
        Assert.NotNull(vault.Session);
        Assert.Equal(expiring.RefreshToken, vault.Session!.RefreshToken);
        Assert.Equal(expiring.Scopes, vault.Session.Scopes);
        Assert.True(vault.Session.CanUsePlan);
    }

    [Fact]
    public void FromJsonRejectsMalformedObjectsAndKeepsValidSessionsWithEmptyEmail()
    {
        Assert.Null(ChatGptSession.FromJson("{}"));
        Assert.Null(ChatGptSession.FromJson("null"));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","email":"","id_token":"i","access_token":"a","refresh_token":"r"}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","email":"","id_token":"i","access_token":"a","refresh_token":"r","scopes":["openid",null]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"subject":"s","email":"","id_token":"i","access_token":"a","refresh_token":"r","scopes":["chatgpt.tokens.use.direct"]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","email":"","id_token":"i","access_token":"a","refresh_token":"r","scopes":["chatgpt.tokens.use.direct"]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","id_token":"i","access_token":"a","refresh_token":"r","scopes":["chatgpt.tokens.use.direct"]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","email":"","access_token":"a","refresh_token":"r","scopes":["chatgpt.tokens.use.direct"]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","email":"","id_token":"i","refresh_token":"r","scopes":["chatgpt.tokens.use.direct"]}"""));
        Assert.Null(ChatGptSession.FromJson("""{"client_id":"c","subject":"s","email":"","id_token":"i","access_token":"a","scopes":["chatgpt.tokens.use.direct"]}"""));

        var emptyEmail = Session() with { Email = "", GrantId = Guid.NewGuid() };
        var parsed = ChatGptSession.FromJson(emptyEmail.ToJson());
        Assert.NotNull(parsed);
        Assert.Equal("", parsed!.Email);
        Assert.True(parsed.CanUsePlan);
        Assert.Equal(emptyEmail.GrantId, parsed.GrantId);
    }

    // ----- helpers -----

    /// <summary>A server that sent its headers and then nothing more.</summary>
    private sealed class SilentReader : TextReader
    {
        public override async ValueTask<string?> ReadLineAsync(CancellationToken cancellationToken)
        {
            await Task.Delay(Timeout.Infinite, cancellationToken);
            return null;
        }
    }

    /// <summary>Hands out its bytes, then fails like a connection that went away.</summary>
    private sealed class BrokenStream(byte[] head) : MemoryStream(head)
    {
        public override int Read(byte[] buffer, int offset, int count) =>
            Position < Length ? base.Read(buffer, offset, count) : throw new IOException("connection reset");

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            Position < Length ? base.ReadAsync(buffer, cancellationToken) : throw new IOException("connection reset");
    }

    private ChatGptSession Session() => new(Issued, "user-123", "learner@example.com", "id", "access-1", "refresh-1",
        _clock.GetUtcNow().AddHours(1), ["openid", "chatgpt.tokens.use.direct"]);

    private static Dictionary<string, string> Callback(SignInAttempt attempt, string clientId) =>
        new() { ["code"] = "the-code", ["state"] = attempt.State, ["client_id"] = clientId };

    private FakeAuth AuthServer(string idToken) =>
        new(_ => Json(new
        {
            access_token = "access-1",
            refresh_token = "refresh-1",
            id_token = idToken,
            token_type = "Bearer",
            expires_in = 3600,
            scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        }), _key);

    private string IdToken(string nonce, string audience = Issued, string issuer = "https://auth.openai.com", int lifetime = 3600, RSA? signer = null)
    {
        var header = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { alg = "RS256", kid = "test-key" }));
        var payload = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new
        {
            iss = issuer,
            aud = audience,
            sub = "user-123",
            email = "learner@example.com",
            nonce,
            exp = _clock.GetUtcNow().AddSeconds(lifetime).ToUnixTimeSeconds(),
        }));
        var signature = (signer ?? _key).SignData(Encoding.ASCII.GetBytes(header + "." + payload), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return header + "." + payload + "." + ChatGptAuth.Base64Url(signature);
    }

    private static Dictionary<string, string> Query(Uri url) =>
        url.Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2))
            .ToDictionary(p => Uri.UnescapeDataString(p[0]), p => Uri.UnescapeDataString(p[1]));

    private static string Event(string type, string? delta = null) =>
        "data: " + JsonSerializer.Serialize(delta is null ? new { type } : (object)new { type, delta }) + "\n\n";

    private static HttpResponseMessage Sse(params string[] events) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(string.Concat(events), Encoding.UTF8, "text/event-stream"),
    };

    private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json"),
    };

    private sealed record FormPost(string Url, Dictionary<string, string> Fields);

    private sealed record ApiCall(string Url, string? Authorization, string Body);

    /// <summary>Plays auth.openai.com (token, revoke, JWKS) and api.openai.com in memory.</summary>
    private sealed class FakeAuth(Func<Dictionary<string, string>, HttpResponseMessage> token, RSA key) : HttpMessageHandler
    {
        public Func<HttpRequestMessage, HttpResponseMessage>? Api { get; init; }

        public List<FormPost> Forms { get; } = [];

        public List<ApiCall> Calls { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.ToString();
            if (url == "https://auth.openai.com/.well-known/jwks.json")
            {
                var p = key.ExportParameters(false);
                return Json(new { keys = new[] { new { kty = "RSA", kid = "test-key", alg = "RS256", n = ChatGptAuth.Base64Url(p.Modulus!), e = ChatGptAuth.Base64Url(p.Exponent!) } } });
            }
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            if (url.StartsWith("https://auth.openai.com/", StringComparison.Ordinal))
            {
                var fields = body.Split('&', StringSplitOptions.RemoveEmptyEntries).Select(p => p.Split('=', 2))
                    .ToDictionary(p => Uri.UnescapeDataString(p[0].Replace('+', ' ')), p => Uri.UnescapeDataString(p[1].Replace('+', ' ')));
                Forms.Add(new FormPost(url, fields));
                return token(fields);
            }
            Calls.Add(new ApiCall(url, request.Headers.Authorization?.ToString(), body));
            return Api?.Invoke(request) ?? new HttpResponseMessage(HttpStatusCode.NotFound);
        }
    }

    private sealed class MemoryVault : ISessionVault
    {
        public ChatGptSession? Session { get; set; }

        public string? RememberedClientId { get; set; }

        public string? RememberedIdToken { get; set; }

        public ChatGptSession? Load() => Session;

        public bool FailSave { get; init; }

        public void Save(ChatGptSession session) => Session = FailSave ? throw new IOException("disk full") : session;

        public void Clear() => Session = null;
    }

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    private sealed class ScriptedHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _send;

        public ScriptedHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send)
            : this((req, _) => send(req))
        {
        }

        public ScriptedHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) => _send = send;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            _send(request, cancellationToken);
    }

    private sealed class HangingStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            await Task.Delay(Timeout.Infinite, cancellationToken);
            return 0;
        }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
