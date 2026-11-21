using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Inkwell.Core.ChatGpt;

/// <summary>One sign-in attempt's secrets; fresh for every attempt and never stored.</summary>
public sealed record SignInAttempt(string State, string Nonce, string Verifier, Uri RedirectUri, string ClientId, Uri AuthorizeUrl)
{
    public bool IsNewRegistration => ClientId == ChatGptAuth.RegistrationClientId;

    public override string ToString() => "ChatGPT sign-in attempt";
}

/// <summary>
/// "Sign in with ChatGPT" for a locally run open-source app, written from OpenAI's published
/// token-sharing documentation: public client, PKCE, loopback redirect on 127.0.0.1, dynamic registration.
/// </summary>
public sealed class ChatGptAuth(HttpClient http, TimeProvider clock)
{
    public const string Issuer = "https://auth.openai.com";
    public const string RegistrationClientId = "dynamic_agent_client";
    public const string Resource = "https://api.openai.com/v1";
    public const string AppName = "Inkwell";
    public const string Scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";

    private const int MaxTokenBodyBytes = 1024 * 1024;
    private const int MaxJwksBytes = 1024 * 1024;
    private static readonly TimeSpan MaxExpiresIn = TimeSpan.FromDays(3650);

    private static readonly Uri AuthorizeEndpoint = new("https://auth.openai.com/api/accounts/authorize");
    private static readonly Uri TokenEndpoint = new("https://auth.openai.com/api/accounts/oauth/token");
    private static readonly Uri RevokeEndpoint = new("https://auth.openai.com/api/accounts/oauth/revoke");
    private static readonly Uri KeysEndpoint = new("https://auth.openai.com/.well-known/jwks.json");

    private static readonly HashSet<string> SignInAgainCodes =
    [
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused",
    ];

    /// <summary>
    /// Builds the browser address. A remembered client id means reauthorization: it carries the
    /// previous ID token and email as hints instead of the app-name hint used for first registration.
    /// </summary>
    public static SignInAttempt Begin(Uri redirectUri, string hostId, string? rememberedClientId, string? idTokenHint, string? emailHint)
    {
        var clientId = string.IsNullOrEmpty(rememberedClientId) ? RegistrationClientId : rememberedClientId;
        var state = RandomText(32);
        var nonce = RandomText(32);
        var verifier = RandomText(64);
        var query = new List<(string, string)>
        {
            ("client_id", clientId),
            ("response_type", "code"),
            ("redirect_uri", redirectUri.ToString()),
            ("scope", Scopes),
            ("resource", Resource),
            ("state", state),
            ("nonce", nonce),
            ("code_challenge", Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)))),
            ("code_challenge_method", "S256"),
            ("ext_agent_host_id", hostId),
        };
        if (clientId == RegistrationClientId)
        {
            query.Add(("agent_name_hint", AppName));
        }
        else
        {
            if (!string.IsNullOrEmpty(idTokenHint)) query.Add(("id_token_hint", idTokenHint));
            if (!string.IsNullOrEmpty(emailHint)) query.Add(("login_hint", emailHint));
        }
        var url = new UriBuilder(AuthorizeEndpoint)
        {
            Query = string.Join("&", query.Select(p => $"{Uri.EscapeDataString(p.Item1)}={Uri.EscapeDataString(p.Item2)}")),
        }.Uri;
        return new SignInAttempt(state, nonce, verifier, redirectUri, clientId, url);
    }

    /// <summary>A stable, opaque id for this computer, generated once by the caller and kept.</summary>
    public static string NewHostId() => "urn:uuid:" + Guid.NewGuid().ToString("D");

    /// <summary>Checks the browser's return and turns the code into a verified session.</summary>
    public async Task<ChatGptSession> FinishAsync(SignInAttempt attempt, IReadOnlyDictionary<string, string> callback, CancellationToken cancel)
    {
        if (!callback.TryGetValue("state", out var state) || state != attempt.State)
        {
            throw new ChatGptException("登录回来的信息对不上（state 不一致），已经忽略；请重新登录。");
        }
        if (callback.TryGetValue("error", out var error))
        {
            if (error == "invalid_client" && !attempt.IsNewRegistration)
            {
                throw new ChatGptException("ChatGPT 不认之前记住的应用编号了，请再试一次重新登录。", signInAgain: true, forgetClient: true);
            }
            throw new ChatGptException(error == "access_denied" ? "你在 ChatGPT 页面上没有同意授权，没有登录。" : "ChatGPT 登录没有完成，请再试一次。");
        }
        if (!callback.TryGetValue("code", out var code) || code.Length == 0) throw new ChatGptException("登录回来没有带授权码，请再试一次。");

        var clientId = attempt.ClientId;
        callback.TryGetValue("client_id", out var returned);
        if (attempt.IsNewRegistration)
        {
            if (string.IsNullOrEmpty(returned) || returned == RegistrationClientId)
            {
                throw new ChatGptException("ChatGPT 没有完成这台电脑上墨水瓶的注册，请再登录一次。");
            }
            clientId = returned;
        }
        else if (!string.IsNullOrEmpty(returned) && returned != attempt.ClientId)
        {
            throw new ChatGptException("ChatGPT 返回了另一个应用编号，为安全起见没有登录。");
        }

        var tokens = await PostForm(TokenEndpoint, new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code",
            ["client_id"] = clientId,
            ["code"] = code,
            ["code_verifier"] = attempt.Verifier,
            ["redirect_uri"] = attempt.RedirectUri.ToString(),
            ["resource"] = Resource,
        }, cancel, signingIn: true, canForgetClient: !attempt.IsNewRegistration);

        var idToken = Text(tokens, "id_token");
        var claims = await VerifyIdTokenAsync(idToken, clientId, attempt.Nonce, cancel);
        return Session(clientId, claims.Subject, claims.Email, idToken, tokens, previousRefresh: null, previousScopes: null, grantId: Guid.NewGuid());
    }

    /// <summary>Gets a new access token; the refresh token rotates and must be stored together with it.</summary>
    public async Task<ChatGptSession> RefreshAsync(ChatGptSession session, CancellationToken cancel)
    {
        var tokens = await PostForm(TokenEndpoint, new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["client_id"] = session.ClientId,
            ["refresh_token"] = session.RefreshToken,
            ["resource"] = Resource,
        }, cancel, signingIn: false, canForgetClient: true);
        var idToken = session.IdToken;
        var email = session.Email;
        if (tokens.TryGetProperty("id_token", out var id) && id.ValueKind != JsonValueKind.Null)
        {
            if (id.ValueKind != JsonValueKind.String) throw BadIdToken();
            if (id.GetString() is { Length: > 0 } nextIdToken)
            {
                var claims = await VerifyIdTokenAsync(nextIdToken, session.ClientId, expectedNonce: null, cancel);
                if (claims.Subject != session.Subject) throw BadIdToken();
                idToken = nextIdToken;
                if (claims.Email.Length > 0) email = claims.Email;
            }
        }
        return Session(session.ClientId, session.Subject, email, idToken, tokens, session.RefreshToken, session.Scopes, session.GrantId);
    }

    /// <summary>Revokes the refresh token. Returns false when revocation could not be confirmed.</summary>
    public async Task<bool> RevokeAsync(ChatGptSession session, CancellationToken cancel)
    {
        using var content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["token"] = session.RefreshToken,
            ["token_type_hint"] = "refresh_token",
            ["client_id"] = session.ClientId,
        });
        using var request = new HttpRequestMessage(HttpMethod.Post, RevokeEndpoint) { Content = content };
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            return response.IsSuccessStatusCode;
        }
        catch (Exception e) when (e is HttpRequestException or IOException)
        {
            return false;
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            return false;
        }
    }

    private ChatGptSession Session(
        string clientId,
        string subject,
        string email,
        string idToken,
        JsonElement tokens,
        string? previousRefresh,
        IReadOnlyList<string>? previousScopes,
        Guid grantId)
    {
        if (tokens.TryGetProperty("token_type", out var tt))
        {
            if (tt.ValueKind != JsonValueKind.String || !string.Equals(tt.GetString(), "Bearer", StringComparison.OrdinalIgnoreCase))
            {
                throw new ChatGptException("ChatGPT 返回的令牌类型不支持，请再登录一次。");
            }
        }
        else if (previousRefresh is null)
        {
            throw new ChatGptException("ChatGPT 没有给出令牌类型，请再登录一次。");
        }

        var access = Text(tokens, "access_token");
        if (access.Length == 0 || access.Any(c => char.IsWhiteSpace(c) || char.IsControl(c)))
        {
            throw new ChatGptException("ChatGPT 没有给出有效的访问凭证，请再登录一次。");
        }
        var refresh = tokens.TryGetProperty("refresh_token", out var r) && r.ValueKind == JsonValueKind.String &&
            r.GetString() is { } nextRefresh && !string.IsNullOrWhiteSpace(nextRefresh)
            ? nextRefresh
            : previousRefresh;
        if (string.IsNullOrWhiteSpace(refresh)) throw new ChatGptException("ChatGPT 没有给长期登录凭证（offline_access），请重新登录并同意授权。");
        var expiresAt = ExpiresAt(clock.GetUtcNow(), tokens);
        var scopes = tokens.TryGetProperty("scope", out var sc) && sc.ValueKind == JsonValueKind.String &&
            sc.GetString() is { } scopeText && !string.IsNullOrWhiteSpace(scopeText)
            ? scopeText.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            : previousScopes ?? [];
        return new ChatGptSession(clientId, subject, email, idToken, access, refresh, expiresAt, scopes) { GrantId = grantId };
    }

    private static DateTimeOffset ExpiresAt(DateTimeOffset now, JsonElement tokens)
    {
        var seconds = tokens.TryGetProperty("expires_in", out var e) && e.ValueKind == JsonValueKind.Number &&
            e.TryGetDouble(out var d) && double.IsFinite(d) && d > 0
            ? Math.Min(d, MaxExpiresIn.TotalSeconds)
            : 3600;
        var remaining = (DateTimeOffset.MaxValue - now).TotalSeconds;
        return seconds >= remaining ? DateTimeOffset.MaxValue : now.AddSeconds(seconds);
    }

    private sealed record IdClaims(string Subject, string Email);

    /// <summary>Signature against OpenAI's published keys, then issuer, audience, expiry and nonce.</summary>
    private async Task<IdClaims> VerifyIdTokenAsync(string idToken, string clientId, string? expectedNonce, CancellationToken cancel)
    {
        try
        {
            return await VerifyIdTokenCoreAsync(idToken, clientId, expectedNonce, cancel);
        }
        catch (Exception e) when (e is HttpRequestException or IOException)
        {
            throw new ChatGptException("取不到 OpenAI 的签名公钥，暂时没法确认登录，请稍后再试。");
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("取 OpenAI 的签名公钥太久没回应，暂时没法确认登录，请稍后再试。");
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or CryptographicException or ArgumentOutOfRangeException)
        {
            // Malformed tokens or key sets are treated as failed verification, never as a crash or a pass.
            throw BadIdToken();
        }
    }

    private async Task<IdClaims> VerifyIdTokenCoreAsync(string idToken, string clientId, string? expectedNonce, CancellationToken cancel)
    {
        var parts = idToken.Split('.');
        if (parts.Length != 3) throw BadIdToken();
        using var header = JsonDocument.Parse(FromBase64Url(parts[0]));
        if (header.RootElement.ValueKind != JsonValueKind.Object || Text(header.RootElement, "alg") != "RS256") throw BadIdToken();
        string? keyId = null;
        if (header.RootElement.TryGetProperty("kid", out var k))
        {
            if (k.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(k.GetString())) throw BadIdToken();
            keyId = k.GetString();
        }

        using var keysResponse = await http.GetAsync(KeysEndpoint, HttpCompletionOption.ResponseHeadersRead, cancel);
        if (!keysResponse.IsSuccessStatusCode) throw new ChatGptException("取不到 OpenAI 的签名公钥，暂时没法确认登录，请稍后再试。");
        var jwksBytes = await ReadBytesAsync(keysResponse.Content, MaxJwksBytes, cancel)
            ?? throw new ChatGptException("OpenAI 的签名公钥列表太大，暂时没法确认登录，请稍后再试。");
        using var keys = JsonDocument.Parse(jwksBytes);
        if (keys.RootElement.ValueKind != JsonValueKind.Object ||
            !keys.RootElement.TryGetProperty("keys", out var keysArray) ||
            keysArray.ValueKind != JsonValueKind.Array)
        {
            throw BadIdToken();
        }
        var signed = Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]);
        var signature = FromBase64Url(parts[2]);
        var verified = keysArray.EnumerateArray()
            .Where(key =>
                Text(key, "kty") == "RSA" &&
                (!key.TryGetProperty("use", out var u) || (u.ValueKind == JsonValueKind.String && u.GetString() == "sig")) &&
                (!key.TryGetProperty("alg", out var a) || (a.ValueKind == JsonValueKind.String && a.GetString() == "RS256")) &&
                (keyId is null || Text(key, "kid") == keyId))
            .Any(key => TryVerifyKey(key, signed, signature));
        if (!verified) throw BadIdToken();

        using var payload = JsonDocument.Parse(FromBase64Url(parts[1]));
        var claims = payload.RootElement;
        if (claims.ValueKind != JsonValueKind.Object) throw BadIdToken();
        var audienceOk = claims.TryGetProperty("aud", out var aud) &&
            (aud.ValueKind == JsonValueKind.String ? aud.GetString() == clientId
                : aud.ValueKind == JsonValueKind.Array && aud.EnumerateArray().Any(a => a.ValueKind == JsonValueKind.String && a.GetString() == clientId));
        var azpOk = !claims.TryGetProperty("azp", out var azp) || (azp.ValueKind == JsonValueKind.String && azp.GetString() == clientId);
        var now = clock.GetUtcNow();
        var expOk = TryUnixTime(claims, "exp", out var expiresAt) && expiresAt > now;
        var nbfOk = !claims.TryGetProperty("nbf", out _) || (TryUnixTime(claims, "nbf", out var notBefore) && notBefore <= now);
        var nonceOk = expectedNonce is null || Text(claims, "nonce") == expectedNonce;
        if (Text(claims, "iss") != Issuer || !audienceOk || !azpOk || !nonceOk || !expOk || !nbfOk)
        {
            throw BadIdToken();
        }
        var subject = Text(claims, "sub");
        if (subject.Length == 0) throw BadIdToken();
        return new IdClaims(subject, claims.TryGetProperty("email", out var mail) && mail.ValueKind == JsonValueKind.String ? mail.GetString()! : "");
    }

    private static bool TryVerifyKey(JsonElement key, byte[] signed, byte[] signature)
    {
        try
        {
            var n = FromBase64Url(Text(key, "n"));
            var e = FromBase64Url(Text(key, "e"));
            if (n.Length == 0 || e.Length == 0) return false;
            using var rsa = RSA.Create(new RSAParameters { Modulus = n, Exponent = e });
            return rsa.VerifyData(signed, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException or ChatGptException)
        {
            return false;
        }
    }

    private static bool TryUnixTime(JsonElement claims, string name, out DateTimeOffset value)
    {
        value = default;
        if (!claims.TryGetProperty(name, out var prop) || prop.ValueKind != JsonValueKind.Number || !prop.TryGetInt64(out var seconds))
        {
            return false;
        }
        if (seconds < DateTimeOffset.MinValue.ToUnixTimeSeconds() || seconds > DateTimeOffset.MaxValue.ToUnixTimeSeconds())
        {
            return false;
        }
        value = DateTimeOffset.FromUnixTimeSeconds(seconds);
        return true;
    }

    private async Task<JsonElement> PostForm(Uri endpoint, Dictionary<string, string> form, CancellationToken cancel, bool signingIn, bool canForgetClient)
    {
        using var content = new FormUrlEncodedContent(form);
        using var request = new HttpRequestMessage(HttpMethod.Post, endpoint) { Content = content };
        HttpResponseMessage response;
        byte[]? bodyBytes;
        try
        {
            response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            try
            {
                bodyBytes = await ReadBytesAsync(response.Content, MaxTokenBodyBytes, cancel);
            }
            catch
            {
                response.Dispose();
                throw;
            }
        }
        catch (Exception e) when (e is HttpRequestException or IOException)
        {
            throw new ChatGptException("连不上 ChatGPT 登录服务，请检查网络；已保存的登录没有被清除。");
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("ChatGPT 登录服务太久没回应，请稍后再试；已保存的登录没有被清除。");
        }
        using (response)
        {
            if (bodyBytes is null)
            {
                throw new ChatGptException("ChatGPT 登录服务的回复太大，请稍后再试；已保存的登录没有被清除。");
            }
            if (response.IsSuccessStatusCode)
            {
                try
                {
                    using var ok = JsonDocument.Parse(bodyBytes);
                    if (ok.RootElement.ValueKind == JsonValueKind.Object) return ok.RootElement.Clone();
                }
                catch (JsonException)
                {
                }
                throw new ChatGptException("ChatGPT 登录服务的回复看不懂，请稍后再试；已保存的登录没有被清除。");
            }
            var code = ErrorCode(bodyBytes);
            if (code is not null && SignInAgainCodes.Contains(code))
            {
                throw new ChatGptException(signingIn ? "授权码已失效，请重新登录。" : "ChatGPT 登录已过期，请重新登录。", signInAgain: true);
            }
            if (code == "invalid_client")
            {
                throw new ChatGptException(
                    signingIn && canForgetClient
                        ? "ChatGPT 不认之前记住的应用编号了，请再试一次重新登录。"
                        : "ChatGPT 不认这个应用编号了，请退出后重新登录。",
                    signInAgain: true,
                    forgetClient: canForgetClient);
            }
            throw new ChatGptException((int)response.StatusCode >= 500
                ? "ChatGPT 登录服务暂时出错，请稍后再试；已保存的登录没有被清除。"
                : $"ChatGPT 拒绝了这次请求（{(int)response.StatusCode}）。");
        }
    }

    private static async Task<byte[]?> ReadBytesAsync(HttpContent content, int maxBytes, CancellationToken cancel)
    {
        if (content.Headers.ContentLength > maxBytes) return null;
        await using var stream = await content.ReadAsStreamAsync(cancel);
        using var memory = new MemoryStream();
        var buffer = new byte[Math.Min(16 * 1024, maxBytes)];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, cancel);
            if (read == 0) return memory.ToArray();
            if (memory.Length + read > maxBytes) return null;
            memory.Write(buffer, 0, read);
        }
    }

    private static string? ErrorCode(byte[] body)
    {
        try
        {
            using var json = JsonDocument.Parse(body);
            return json.RootElement.ValueKind == JsonValueKind.Object && json.RootElement.TryGetProperty("error", out var e)
                ? e.ValueKind == JsonValueKind.String ? e.GetString() : e.ValueKind == JsonValueKind.Object ? Text(e, "code") : null
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string Text(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()!
            : "";

    private static ChatGptException BadIdToken() => new("ChatGPT 返回的身份信息没有通过校验，为安全起见没有登录。");

    private static string RandomText(int bytes) => Base64Url(RandomNumberGenerator.GetBytes(bytes));

    internal static string Base64Url(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    internal static byte[] FromBase64Url(string text)
    {
        var padded = text.Replace('-', '+').Replace('_', '/');
        padded += (padded.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        try
        {
            return Convert.FromBase64String(padded);
        }
        catch (FormatException)
        {
            throw BadIdToken();
        }
    }
}
