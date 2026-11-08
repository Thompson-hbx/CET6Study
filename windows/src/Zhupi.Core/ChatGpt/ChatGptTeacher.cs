using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Zhupi.Core.Teacher;

namespace Zhupi.Core.ChatGpt;

public sealed record PlanModel(string Slug, string Name);

/// <summary>
/// Marks pages with the learner's own ChatGPT plan through <c>POST /v1/responses</c>, as the plan-usage
/// documentation requires: <c>store: false</c>, <c>stream: true</c>, and only a <c>response.completed</c>
/// event counts as an answer. Nothing is retried automatically.
/// </summary>
public sealed class ChatGptTeacher(HttpClient http, ChatGptAuth auth, ISessionVault vault, TimeProvider clock, string model, Depth depth = Depth.Default) : ITeacher
{
    private static readonly Uri Api = new("https://api.openai.com/v1/");
    private static readonly SemaphoreSlim Refreshing = new(1, 1);

    public async Task<IReadOnlyList<PlanModel>> ModelsAsync(CancellationToken cancel)
    {
        var session = await FreshSession(cancel);
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(Api, "models"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", session.AccessToken);
        using var response = await Send(request, HttpCompletionOption.ResponseContentRead, cancel);
        try
        {
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
            return json.RootElement.GetProperty("models").EnumerateArray()
                .Where(m => Text(m, "visibility") == "list" && Text(m, "slug").Length > 0)
                .Select(m => new PlanModel(Text(m, "slug"), Text(m, "display_name") is { Length: > 0 } name ? name : Text(m, "slug")))
                .ToList();
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            throw new ChatGptException("ChatGPT 返回的模型列表看不懂，请稍后再试。");
        }
    }

    /// <summary>
    /// The plan-usage documentation does not list a JSON output setting, so <paramref name="jsonReply"/> relies on
    /// the instruction alone; the caller still validates the reply.
    /// </summary>
    public async Task<string> AskAsync(string instruction, string material, bool jsonReply, CancellationToken cancel)
    {
        if (model.Trim().Length == 0) throw new ChatGptException("先在「设置」里选一个 ChatGPT 模型。");
        var session = await FreshSession(cancel);
        var body = new JsonObject
        {
            ["model"] = model.Trim(),
            ["instructions"] = instruction,
            ["input"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = material }),
            ["store"] = false,
            ["stream"] = true,
        };
        // Not on the plan-usage list of unsupported fields; a model that refuses a level answers with
        // subscription_sharing_unsupported_capability, which Explain turns into advice to change the depth.
        if (depth.Effort() is { } effort) body["reasoning"] = new JsonObject { ["effort"] = effort };
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(Api, "responses"))
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", session.AccessToken);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
        using var response = await Send(request, HttpCompletionOption.ResponseHeadersRead, cancel);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(cancel), Encoding.UTF8);
        return await ReadStream(reader, cancel);
    }

    /// <summary>Collects output text until <c>response.completed</c>; anything else ends without an answer.</summary>
    internal static async Task<string> ReadStream(TextReader reader, CancellationToken cancel)
    {
        var text = new StringBuilder();
        var data = new StringBuilder();
        while (await reader.ReadLineAsync(cancel) is { } line)
        {
            if (line.StartsWith("data:", StringComparison.Ordinal))
            {
                data.Append(line.AsSpan(5).TrimStart(' '));
                continue;
            }
            if (line.Length != 0 || data.Length == 0) continue;
            var payload = data.ToString();
            data.Clear();
            if (payload == "[DONE]") break;
            JsonDocument json;
            try
            {
                json = JsonDocument.Parse(payload);
            }
            catch (JsonException)
            {
                throw new ChatGptException("ChatGPT 的回复格式看不懂，没有保存。");
            }
            using (json)
            {
                var root = json.RootElement;
                switch (Text(root, "type"))
                {
                    case "response.output_text.delta":
                        text.Append(Text(root, "delta"));
                        break;
                    case "response.completed":
                        return text.Length > 0 ? text.ToString() : throw new ChatGptException("ChatGPT 交了白卷，没有保存。");
                    case "response.incomplete":
                        throw new ChatGptException("ChatGPT 没写完就停了（回复不完整），没有保存；可以换个模型或缩短文字再请一次。");
                    case "response.failed":
                        throw Explain(root.TryGetProperty("response", out var r) && r.TryGetProperty("error", out var e) ? Text(e, "code") : null, 0);
                    case "error":
                        throw Explain(Text(root, "code") is { Length: > 0 } c ? c : root.TryGetProperty("error", out var e2) ? Text(e2, "code") : null, 0);
                }
            }
        }
        throw new ChatGptException("和 ChatGPT 的连接中途断了，没有收到完整批改，没有保存。");
    }

    private async Task<ChatGptSession> FreshSession(CancellationToken cancel)
    {
        var session = vault.Load() ?? throw new ChatGptException("还没登录 ChatGPT：到「设置 → 请老师」登录。", signInAgain: true);
        if (!session.CanUsePlan)
        {
            throw new ChatGptException("这个 ChatGPT 账号没有授权朱批使用套餐额度，请在设置里重新登录并同意授权。", signInAgain: true);
        }
        if (session.ExpiresAt - clock.GetUtcNow() > TimeSpan.FromMinutes(5)) return session;
        // One refresh at a time: the refresh token rotates, so a second concurrent refresh would reuse a spent token.
        await Refreshing.WaitAsync(cancel);
        try
        {
            var latest = vault.Load() ?? throw new ChatGptException("ChatGPT 登录已经退出，请重新登录。", signInAgain: true);
            if (latest.ExpiresAt - clock.GetUtcNow() > TimeSpan.FromMinutes(5)) return latest;
            try
            {
                var renewed = await auth.RefreshAsync(latest, cancel);
                vault.Save(renewed);
                return renewed;
            }
            catch (ChatGptException e) when (e.SignInAgain)
            {
                vault.Clear();
                throw;
            }
        }
        finally
        {
            Refreshing.Release();
        }
    }

    private async Task<HttpResponseMessage> Send(HttpRequestMessage request, HttpCompletionOption option, CancellationToken cancel)
    {
        HttpResponseMessage response;
        try
        {
            response = await http.SendAsync(request, option, cancel);
        }
        catch (HttpRequestException)
        {
            throw new ChatGptException("连不上 ChatGPT，请检查网络。");
        }
        catch (TaskCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("ChatGPT 太久没回应，已经停下，没有保存。");
        }
        if (response.IsSuccessStatusCode) return response;
        var status = (int)response.StatusCode;
        string? code = null;
        try
        {
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
            if (json.RootElement.ValueKind == JsonValueKind.Object && json.RootElement.TryGetProperty("error", out var e))
            {
                code = e.ValueKind == JsonValueKind.Object ? Text(e, "code") : null;
            }
        }
        catch (JsonException)
        {
        }
        response.Dispose();
        throw Explain(code, status);
    }

    /// <summary>The documented plan-usage error codes, in words; unknown ones fall back to the HTTP status.</summary>
    internal static ChatGptException Explain(string? code, int status) => code switch
    {
        "subscription_sharing_usage_limit_exceeded" =>
            new("ChatGPT 套餐额度这段时间用完了，请到 chatgpt.com/settings/usage 查看，过一阵再请老师。"),
        "subscription_sharing_user_not_eligible" => new("这个 ChatGPT 账号的套餐不能给第三方应用用。可以改用 DeepSeek 等 API Key。"),
        "subscription_sharing_usage_unavailable" or "subscription_sharing_user_unavailable" =>
            new("ChatGPT 套餐服务暂时不可用，稍后再试；登录仍然保留。"),
        "subscription_sharing_unsupported_capability" => new("这个模型不支持这样的请求（可能是批改深度这一档），请在设置里把深度改回「默认」或换一个模型。"),
        "subscription_sharing_invalid_user" => new("ChatGPT 登录已失效，请重新登录。", signInAgain: true),
        "subscription_sharing_route_not_supported" or "chatpass_v2_scope_not_authorized" or "chatpass_v2_invalid_authorization_context" =>
            new("ChatGPT 不接受朱批这次的调用方式，请重新登录；若仍不行，请改用 API Key。", signInAgain: true),
        _ => status switch
        {
            401 => new("ChatGPT 不认这次的登录，请重新登录。", signInAgain: true),
            403 => new("ChatGPT 拒绝了这次调用（可能是账号权限或所在地区不支持）。"),
            429 => new("请求太频繁或套餐额度用完了，稍后再试。"),
            503 => new("ChatGPT 暂时不可用，稍后再试。"),
            0 => new("ChatGPT 这次没有批完，没有保存。"),
            _ => new($"ChatGPT 返回了错误（{status}），没有保存。"),
        },
    };

    private static string Text(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "";
}
