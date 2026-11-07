using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Zhupi.Core.ChatGpt;

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
    public const string AppName = "Zhupi";
    public const string Scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";

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
            throw new ChatGptException(error == "access_denied" ? "你在 ChatGPT 页面上没有同意授权，没有登录。" : "ChatGPT 登录没有完成，请再试一次。");
        }
        if (!callback.TryGetValue("code", out var code) || code.Length == 0) throw new ChatGptException("登录回来没有带授权码，请再试一次。");

        var clientId = attempt.ClientId;
        callback.TryGetValue("client_id", out var returned);
        if (attempt.IsNewRegistration)
        {
            if (string.IsNullOrEmpty(returned) || returned == RegistrationClientId)
            {
                throw new ChatGptException("ChatGPT 没有完成这台电脑上朱批的注册，请再登录一次。");
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
        }, cancel, signingIn: true);

        var idToken = Text(tokens, "id_token");
        var claims = await VerifyIdTokenAsync(idToken, clientId, attempt.Nonce, cancel);
        return Session(clientId, claims.Subject, claims.Email, idToken, tokens, previousRefresh: null);
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
        }, cancel, signingIn: false);
        var idToken = tokens.TryGetProperty("id_token", out var id) && id.ValueKind == JsonValueKind.String ? id.GetString()! : session.IdToken;
        return Session(session.ClientId, session.Subject, session.Email, idToken, tokens, session.RefreshToken);
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
        try
        {
            using var response = await http.PostAsync(RevokeEndpoint, content, cancel);
            return response.IsSuccessStatusCode;
        }
        catch (HttpRequestException)
        {
            return false;
        }
    }

    private ChatGptSession Session(string clientId, string subject, string email, string idToken, JsonElement tokens, string? previousRefresh)
    {
        var access = Text(tokens, "access_token");
        if (access.Length == 0) throw new ChatGptException("ChatGPT 没有给出访问凭证，请再登录一次。");
        var refresh = tokens.TryGetProperty("refresh_token", out var r) && r.ValueKind == JsonValueKind.String ? r.GetString()! : previousRefresh;
        if (string.IsNullOrEmpty(refresh)) throw new ChatGptException("ChatGPT 没有给长期登录凭证（offline_access），请重新登录并同意授权。");
        var seconds = tokens.TryGetProperty("expires_in", out var e) && e.TryGetInt32(out var s) ? s : 3600;
        var scopes = tokens.TryGetProperty("scope", out var sc) && sc.ValueKind == JsonValueKind.String
            ? sc.GetString()!.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            : [];
        return new ChatGptSession(clientId, subject, email, idToken, access, refresh, clock.GetUtcNow().AddSeconds(seconds), scopes);
    }

    private sealed record IdClaims(string Subject, string Email);

    /// <summary>Signature against OpenAI's published keys, then issuer, audience, expiry and nonce.</summary>
    private async Task<IdClaims> VerifyIdTokenAsync(string idToken, string clientId, string nonce, CancellationToken cancel)
    {
        var parts = idToken.Split('.');
        if (parts.Length != 3) throw BadIdToken();
        using var header = JsonDocument.Parse(FromBase64Url(parts[0]));
        if (Text(header.RootElement, "alg") != "RS256") throw BadIdToken();
        var keyId = header.RootElement.TryGetProperty("kid", out var k) ? k.GetString() : null;

        using var keysResponse = await http.GetAsync(KeysEndpoint, cancel);
        if (!keysResponse.IsSuccessStatusCode) throw new ChatGptException("取不到 OpenAI 的签名公钥，暂时没法确认登录，请稍后再试。");
        using var keys = JsonDocument.Parse(await keysResponse.Content.ReadAsStringAsync(cancel));
        var signed = Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]);
        var signature = FromBase64Url(parts[2]);
        var verified = keys.RootElement.GetProperty("keys").EnumerateArray()
            .Where(key => Text(key, "kty") == "RSA" && (keyId is null || Text(key, "kid") == keyId))
            .Any(key =>
            {
                using var rsa = RSA.Create(new RSAParameters
                {
                    Modulus = FromBase64Url(Text(key, "n")),
                    Exponent = FromBase64Url(Text(key, "e")),
                });
                return rsa.VerifyData(signed, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            });
        if (!verified) throw BadIdToken();

        using var payload = JsonDocument.Parse(FromBase64Url(parts[1]));
        var claims = payload.RootElement;
        var audienceOk = claims.TryGetProperty("aud", out var aud) &&
            (aud.ValueKind == JsonValueKind.String ? aud.GetString() == clientId
                : aud.ValueKind == JsonValueKind.Array && aud.EnumerateArray().Any(a => a.GetString() == clientId));
        var expires = claims.TryGetProperty("exp", out var exp) && exp.TryGetInt64(out var seconds) ? seconds : 0;
        if (Text(claims, "iss") != Issuer || !audienceOk || Text(claims, "nonce") != nonce ||
            DateTimeOffset.FromUnixTimeSeconds(expires) <= clock.GetUtcNow())
        {
            throw BadIdToken();
        }
        var subject = Text(claims, "sub");
        if (subject.Length == 0) throw BadIdToken();
        return new IdClaims(subject, claims.TryGetProperty("email", out var mail) && mail.ValueKind == JsonValueKind.String ? mail.GetString()! : "");
    }

    private async Task<JsonElement> PostForm(Uri endpoint, Dictionary<string, string> form, CancellationToken cancel, bool signingIn)
    {
        using var content = new FormUrlEncodedContent(form);
        HttpResponseMessage response;
        try
        {
            response = await http.PostAsync(endpoint, content, cancel);
        }
        catch (HttpRequestException)
        {
            throw new ChatGptException("连不上 ChatGPT 登录服务，请检查网络；已保存的登录没有被清除。");
        }
        using (response)
        {
            var body = await response.Content.ReadAsStringAsync(cancel);
            if (response.IsSuccessStatusCode)
            {
                using var ok = JsonDocument.Parse(body);
                return ok.RootElement.Clone();
            }
            var code = ErrorCode(body);
            if (code is not null && SignInAgainCodes.Contains(code))
            {
                throw new ChatGptException(signingIn ? "授权码已失效，请重新登录。" : "ChatGPT 登录已过期，请重新登录。", signInAgain: true);
            }
            if (code == "invalid_client") throw new ChatGptException("ChatGPT 不认这个应用编号了，请退出后重新登录。", signInAgain: true);
            throw new ChatGptException((int)response.StatusCode >= 500
                ? "ChatGPT 登录服务暂时出错，请稍后再试；已保存的登录没有被清除。"
                : $"ChatGPT 拒绝了这次请求（{(int)response.StatusCode}）。");
        }
    }

    private static string? ErrorCode(string body)
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
