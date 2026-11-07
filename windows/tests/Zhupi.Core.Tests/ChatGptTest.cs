using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Zhupi.Core.ChatGpt;

namespace Zhupi.Core.Tests;

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
        Assert.Equal("Zhupi", query["agent_name_hint"]);
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

        var reply = await teacher.AskForJsonAsync("instruction", "material", CancellationToken.None);

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
            new ChatGptTeacher(new HttpClient(api), new ChatGptAuth(new HttpClient(api), _clock), noPlan, _clock, "m").AskForJsonAsync("i", "m", CancellationToken.None));

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

        var error = await Assert.ThrowsAsync<ChatGptException>(() => teacher.AskForJsonAsync("i", "m", CancellationToken.None));

        Assert.True(error.SignInAgain);
    }

    [Fact]
    public async Task ModelsKeepListedOnesInServerOrder()
    {
        var api = new FakeAuth(_ => new HttpResponseMessage(HttpStatusCode.OK), _key)
        {
            Api = _ => Json(new { models = new object[]
            {
                new { slug = "gpt-b", display_name = "B", visibility = "list" },
                new { slug = "gpt-hidden", display_name = "H", visibility = "hide" },
                new { slug = "gpt-a", display_name = "", visibility = "list" },
            } }),
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
        var waiting = loopback.WaitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(10)).Token);

        using var http = new HttpClient();
        var stray = await http.GetAsync(new Uri(loopback.RedirectUri, "/favicon.ico"));
        var answer = await http.GetAsync(loopback.RedirectUri + "?code=abc%2B1&state=s&client_id=oaiapp_x");

        Assert.Equal(HttpStatusCode.NotFound, stray.StatusCode);
        Assert.Equal(HttpStatusCode.OK, answer.StatusCode);
        var query = await waiting;
        Assert.Equal(("abc+1", "s", "oaiapp_x"), (query["code"], query["state"], query["client_id"]));
    }

    // ----- helpers -----

    private ChatGptSession Session() => new(Issued, "user-123", "learner@example.com", "id", "access-1", "refresh-1",
        _clock.GetUtcNow().AddHours(1), ["openid", "chatgpt.tokens.use.direct"]);

    private static Dictionary<string, string> Callback(SignInAttempt attempt, string clientId) =>
        new() { ["code"] = "the-code", ["state"] = attempt.State, ["client_id"] = clientId };

    private FakeAuth AuthServer(string idToken) =>
        new(_ => Json(new { access_token = "access-1", refresh_token = "refresh-1", id_token = idToken, token_type = "Bearer", expires_in = 3600,
            scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct" }), _key);

    private string IdToken(string nonce, string audience = Issued, string issuer = "https://auth.openai.com", int lifetime = 3600, RSA? signer = null)
    {
        var header = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { alg = "RS256", kid = "test-key" }));
        var payload = ChatGptAuth.Base64Url(JsonSerializer.SerializeToUtf8Bytes(new
        {
            iss = issuer, aud = audience, sub = "user-123", email = "learner@example.com", nonce,
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

        public void Save(ChatGptSession session) => Session = session;

        public void Clear() => Session = null;
    }

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
