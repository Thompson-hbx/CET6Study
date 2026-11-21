using System.Text.Json;
using System.Text.Json.Serialization;

namespace Inkwell.Core.ChatGpt;

/// <summary>
/// One signed-in ChatGPT account for this app. Holds tokens, so it only ever lives in memory or in the
/// encrypted vault; <see cref="ToString"/> is overridden so it cannot be printed by accident.
/// </summary>
public sealed record ChatGptSession(
    [property: JsonPropertyName("client_id")] string ClientId,
    [property: JsonPropertyName("subject")] string Subject,
    [property: JsonPropertyName("email")] string Email,
    [property: JsonPropertyName("id_token")] string IdToken,
    [property: JsonPropertyName("access_token")] string AccessToken,
    [property: JsonPropertyName("refresh_token")] string RefreshToken,
    [property: JsonPropertyName("expires_at")] DateTimeOffset ExpiresAt,
    [property: JsonPropertyName("scopes")] IReadOnlyList<string> Scopes)
{
    public const string PlanScope = "chatgpt.tokens.use.direct";

    public Guid GrantId { get; init; }

    internal bool SameGrant(ChatGptSession other) =>
        GrantId == other.GrantId && ClientId == other.ClientId && Subject == other.Subject;

    /// <summary>Signed in is not enough: the plan can only be used when this scope was granted.</summary>
    public bool CanUsePlan => Scopes.Contains(PlanScope);

    public string ToJson() => JsonSerializer.Serialize(this);

    public static ChatGptSession? FromJson(string json)
    {
        try
        {
            var session = JsonSerializer.Deserialize<ChatGptSession>(json);
            if (session is null ||
                session.ClientId is null ||
                session.Subject is null ||
                session.Email is null ||
                session.IdToken is null ||
                session.AccessToken is null ||
                session.RefreshToken is null ||
                session.Scopes is null ||
                session.Scopes.Any(s => s is null))
            {
                return null;
            }
            return session;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public override string ToString() => $"ChatGPT session for {Email}";
}

/// <summary>Where the session is kept; the Windows app encrypts it with DPAPI.</summary>
public interface ISessionVault
{
    ChatGptSession? Load();

    void Save(ChatGptSession session);

    void Clear();

    /// <summary>The issued client id survives sign-out so the next sign-in reuses the registration.</summary>
    string? RememberedClientId { get; set; }

    /// <summary>The ID token of the last successful sign-in, erased on explicit sign-out.</summary>
    string? RememberedIdToken { get; set; }
}

/// <summary>A sign-in or call that failed in a way the learner can act on. Messages never contain tokens.</summary>
public sealed class ChatGptException(string message, bool signInAgain = false, bool forgetClient = false) : Exception(message)
{
    /// <summary>True when the stored tokens are no longer usable and the learner must sign in again.</summary>
    public bool SignInAgain { get; } = signInAgain;

    /// <summary>True when the remembered dynamic client registration was rejected and should be forgotten.</summary>
    public bool ForgetClient { get; } = forgetClient;
}
