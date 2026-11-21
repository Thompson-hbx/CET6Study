using System.Net.Http.Headers;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Inkwell.Core.Teacher;

namespace Inkwell.Core.ChatGpt;

public sealed record PlanModel(string Slug, string Name);

/// <summary>
/// Marks pages with the learner's own ChatGPT plan through <c>POST /v1/responses</c>, as the plan-usage
/// documentation requires: <c>store: false</c>, <c>stream: true</c>, and only a <c>response.completed</c>
/// event counts as an answer. Nothing is retried automatically.
/// </summary>
public sealed class ChatGptTeacher(HttpClient http, ChatGptAuth auth, ISessionVault vault, TimeProvider clock, string model, Depth depth = Depth.Default) : ITeacher
{
    private const int MaxModelsBytes = 2 * 1024 * 1024;
    private const int MaxErrorBytes = 64 * 1024;
    private const int MaxStreamBytes = 8 * 1024 * 1024;
    private const int MaxStreamChars = 4 * 1024 * 1024;

    private static readonly Uri Api = new("https://api.openai.com/v1/");
    private static readonly ConditionalWeakTable<ISessionVault, SemaphoreSlim> RefreshingByVault = new();
    private static readonly ConditionalWeakTable<ISessionVault, ChatGptSession> SigningOut = new();
    private readonly ChatGptSession? _authorizedGrant;

    public ChatGptTeacher(HttpClient http, ChatGptAuth auth, ISessionVault vault, TimeProvider clock, string model, Depth depth,
        ChatGptSession authorizedGrant) : this(http, auth, vault, clock, model, depth)
    {
        _authorizedGrant = authorizedGrant;
    }

    // Generous, because deep reasoning can pause between events; a silence this long means the stream is stuck.
    private static readonly TimeSpan StreamIdleLimit = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan StreamTotalLimit = TimeSpan.FromMinutes(15);

    internal TimeSpan RefreshLimit { get; init; } = TimeSpan.FromSeconds(30);

    internal TimeSpan ModelsReadLimit { get; init; } = TimeSpan.FromSeconds(30);

    internal TimeSpan ErrorBodyLimit { get; init; } = TimeSpan.FromSeconds(5);

    private static SemaphoreSlim RefreshLock(ISessionVault vault) =>
        RefreshingByVault.GetValue(vault, static _ => new SemaphoreSlim(1, 1));

    public async Task<IReadOnlyList<PlanModel>> ModelsAsync(CancellationToken cancel)
    {
        var session = await FreshSession(cancel);
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(Api, "models"));
        using var response = await SendForGrant(request, session, HttpCompletionOption.ResponseHeadersRead, cancel);
        using var readTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        readTimeout.CancelAfter(ModelsReadLimit);
        try
        {
            var bytes = await ReadBoundedBytesAsync(response.Content, MaxModelsBytes, readTimeout.Token)
                ?? throw new ChatGptException("ChatGPT 返回的模型列表太大，请稍后再试。");
            using var json = JsonDocument.Parse(bytes);
            return json.RootElement.GetProperty("models").EnumerateArray()
                .Where(m => Text(m, "visibility") == "list" && Text(m, "slug").Length > 0)
                .Select(m => new PlanModel(Text(m, "slug"), Text(m, "display_name") is { Length: > 0 } name ? name : Text(m, "slug")))
                .ToList();
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("ChatGPT 太久没回应，已经停下，没有保存。");
        }
        catch (Exception e) when (e is IOException or HttpRequestException)
        {
            throw new ChatGptException("连不上 ChatGPT，请检查网络。");
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
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
        using var response = await SendForGrant(request, session, HttpCompletionOption.ResponseHeadersRead, cancel);
        try
        {
            await using var bounded = new BoundedReadStream(await response.Content.ReadAsStreamAsync(cancel), MaxStreamBytes);
            using var reader = new StreamReader(bounded, Encoding.UTF8);
            return await ReadStream(reader, cancel, StreamIdleLimit, StreamTotalLimit, MaxStreamChars);
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("ChatGPT 太久没回应，已经停下，没有保存。");
        }
        catch (Exception e) when (e is IOException or HttpRequestException)
        {
            // A dropped connection mid-stream surfaces here, after the headers; the partial answer is discarded.
            throw new ChatGptException("和 ChatGPT 的连接中途断了，没有收到完整批改，没有保存。");
        }
    }

    /// <summary>Collects output text until <c>response.completed</c>; anything else ends without an answer.</summary>
    internal static async Task<string> ReadStream(
        TextReader reader, CancellationToken cancel, TimeSpan? idle = null, TimeSpan? total = null, int maxChars = MaxStreamChars)
    {
        var text = new StringBuilder();
        var data = new StringBuilder();
        var totalChars = 0;
        var sawRefusal = false;
        using var totalCts = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        if (total is { } totalLimit) totalCts.CancelAfter(totalLimit);
        // HttpClient.Timeout stops at the response headers, so a stream that goes silent afterwards needs its own limit.
        using var quiet = CancellationTokenSource.CreateLinkedTokenSource(totalCts.Token);
        while (true)
        {
            quiet.CancelAfter(idle ?? Timeout.InfiniteTimeSpan);
            string? line;
            try
            {
                line = await reader.ReadLineAsync(quiet.Token);
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                throw totalCts.IsCancellationRequested
                    ? new ChatGptException("ChatGPT 批改时间太长，已经停下，没有保存；可以缩短文字或换个模型再试。")
                    : new ChatGptException("ChatGPT 太久没有动静，已经停下，没有保存；可以稍后再请一次。");
            }
            if (line is null) break;
            totalChars += line.Length + 1;
            if (totalChars > maxChars) throw new ChatGptException("ChatGPT 的回复超过大小限制，没有保存。");
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
                    case "response.refusal.delta":
                        sawRefusal = true;
                        break;
                    case "response.refusal.done":
                        sawRefusal = true;
                        if (text.Length == 0) throw new ChatGptException("ChatGPT 拒绝回答这个请求，没有保存。");
                        break;
                    case "response.completed":
                        if (text.Length == 0) AppendCompletedOutput(root, text, ref sawRefusal);
                        if (text.Length > 0) return text.ToString();
                        throw new ChatGptException(sawRefusal ? "ChatGPT 拒绝回答这个请求，没有保存。" : "ChatGPT 交了白卷，没有保存。");
                    case "response.incomplete":
                        throw new ChatGptException("ChatGPT 没写完就停了（回复不完整），没有保存；可以换个模型或缩短文字再请一次。");
                    case "response.failed":
                        throw Explain(root.TryGetProperty("response", out var r) && r.ValueKind == JsonValueKind.Object && r.TryGetProperty("error", out var e)
                            ? Text(e, "code") : null, 0);
                    case "error":
                        throw Explain(Text(root, "code") is { Length: > 0 } c ? c : root.TryGetProperty("error", out var e2) ? Text(e2, "code") : null, 0);
                }
            }
        }
        throw new ChatGptException("和 ChatGPT 的连接中途断了，没有收到完整批改，没有保存。");
    }

    private static void AppendCompletedOutput(JsonElement root, StringBuilder text, ref bool sawRefusal)
    {
        if (!root.TryGetProperty("response", out var response) || response.ValueKind != JsonValueKind.Object ||
            !response.TryGetProperty("output", out var output) || output.ValueKind != JsonValueKind.Array)
        {
            return;
        }
        foreach (var item in output.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            switch (Text(item, "type"))
            {
                case "output_text":
                    text.Append(Text(item, "text"));
                    break;
                case "refusal":
                    sawRefusal = true;
                    break;
            }
            if (!item.TryGetProperty("content", out var content) || content.ValueKind != JsonValueKind.Array) continue;
            foreach (var part in content.EnumerateArray())
            {
                if (part.ValueKind != JsonValueKind.Object) continue;
                switch (Text(part, "type"))
                {
                    case "output_text":
                        text.Append(Text(part, "text"));
                        break;
                    case "refusal":
                        sawRefusal = true;
                        break;
                }
            }
        }
    }

    public static async Task<ChatGptSession?> EndSessionAsync(ISessionVault vault, CancellationToken cancel)
    {
        ChatGptSession expected;
        lock (vault)
        {
            if (vault.Load() is not { } current || SigningOut.TryGetValue(vault, out _)) return null;
            expected = current;
            SigningOut.Add(vault, expected);
        }

        try
        {
            var refreshing = RefreshLock(vault);
            // Let an in-flight rotation hand off the latest token, but block new calls immediately.
            await refreshing.WaitAsync(cancel);
            try
            {
                lock (vault)
                {
                    var current = vault.Load();
                    if (current is not null && !expected.SameGrant(current)) return null;
                    vault.RememberedIdToken = null;
                    vault.Clear();
                    return current ?? expected;
                }
            }
            finally
            {
                refreshing.Release();
            }
        }
        finally
        {
            lock (vault) SigningOut.Remove(vault);
        }
    }

    private async Task<ChatGptSession> FreshSession(CancellationToken cancel)
    {
        ChatGptSession session;
        lock (vault)
        {
            var current = vault.Load() ?? throw new ChatGptException("还没登录 ChatGPT：到「设置 → 请老师」登录。", signInAgain: true);
            session = CheckGrant(current, _authorizedGrant ?? current);
        }
        if (session.ExpiresAt - clock.GetUtcNow() > TimeSpan.FromMinutes(5)) return session;

        var refreshing = RefreshLock(vault);
        // Rotating refresh tokens must never be reused by concurrent requests.
        await refreshing.WaitAsync(cancel);
        try
        {
            ChatGptSession latest;
            lock (vault) latest = CurrentGrant(session);
            if (latest.ExpiresAt - clock.GetUtcNow() > TimeSpan.FromMinutes(5)) return latest;

            using var refreshTimeout = new CancellationTokenSource(RefreshLimit, clock);
            ChatGptSession renewed;
            try
            {
                // Do NOT link caller cancel: the server may already have rotated this grant.
                renewed = (await auth.RefreshAsync(latest, refreshTimeout.Token)) with { GrantId = latest.GrantId };
            }
            catch (OperationCanceledException) when (refreshTimeout.IsCancellationRequested)
            {
                throw new ChatGptException("ChatGPT 登录续期太久没回应，请稍后再试；已保存的登录没有被清除。");
            }
            catch (ChatGptException e) when (e.SignInAgain)
            {
                lock (vault)
                {
                    if (vault.Load() is { } current && latest.SameGrant(current) && latest.RefreshToken == current.RefreshToken)
                    {
                        if (e.ForgetClient)
                        {
                            if (vault.RememberedClientId == current.ClientId) vault.RememberedClientId = null;
                            vault.RememberedIdToken = null;
                        }
                        vault.Clear();
                    }
                }
                throw;
            }

            lock (vault)
            {
                if (CurrentGrant(latest, allowSigningOut: true).RefreshToken != latest.RefreshToken)
                {
                    throw GrantChanged();
                }
                if (!renewed.CanUsePlan)
                {
                    vault.Clear();
                    RequirePlanScope(renewed);
                }
                try
                {
                    vault.Save(renewed);
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    throw new ChatGptException("ChatGPT 登录续期了，但没能存到这台电脑上，这次没有发请求；请在设置里重新登录。");
                }
            }
            cancel.ThrowIfCancellationRequested();
            return renewed;
        }
        finally
        {
            refreshing.Release();
        }
    }

    private ChatGptSession CurrentGrant(ChatGptSession expected, bool allowSigningOut = false) =>
        CheckGrant(vault.Load(), expected, allowSigningOut);

    private ChatGptSession CheckGrant(ChatGptSession? current, ChatGptSession expected, bool allowSigningOut = false)
    {
        if (current is null)
        {
            throw new ChatGptException("ChatGPT 登录已经退出，请重新登录。", signInAgain: true);
        }
        if (!expected.SameGrant(current) ||
            (!allowSigningOut && SigningOut.TryGetValue(vault, out var ending) && expected.SameGrant(ending)))
        {
            throw GrantChanged();
        }
        RequirePlanScope(current);
        return current;
    }

    private static ChatGptException GrantChanged() => new("ChatGPT 登录状态已经变化，这次没有发请求。");

    private static void RequirePlanScope(ChatGptSession session)
    {
        if (!session.CanUsePlan)
        {
            throw new ChatGptException("这个 ChatGPT 账号没有授权墨水瓶使用套餐额度，请在设置里重新登录并同意授权。", signInAgain: true);
        }
    }

    private async Task<HttpResponseMessage> SendForGrant(
        HttpRequestMessage request, ChatGptSession expected, HttpCompletionOption option, CancellationToken cancel)
    {
        lock (vault)
        {
            var current = CurrentGrant(expected);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", current.AccessToken);
        }
        return await Send(request, option, cancel);
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
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ChatGptException("ChatGPT 太久没回应，已经停下，没有保存。");
        }
        if (response.IsSuccessStatusCode) return response;
        using (response)
        {
            var status = (int)response.StatusCode;
            string? code = null;
            using var errorTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancel);
            errorTimeout.CancelAfter(ErrorBodyLimit);
            try
            {
                if (await ReadBoundedBytesAsync(response.Content, MaxErrorBytes, errorTimeout.Token) is { } bytes)
                {
                    using var json = JsonDocument.Parse(bytes);
                    if (json.RootElement.ValueKind == JsonValueKind.Object && json.RootElement.TryGetProperty("error", out var e))
                    {
                        code = e.ValueKind == JsonValueKind.Object ? Text(e, "code") : null;
                    }
                }
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                // Timed out reading the error body; fall back to status code.
            }
            catch (Exception e) when (e is JsonException or IOException or HttpRequestException)
            {
                // The status alone still explains the failure when the error body is unreadable.
            }
            throw Explain(code, status);
        }
    }

    private static async Task<byte[]?> ReadBoundedBytesAsync(HttpContent content, int maxBytes, CancellationToken cancel)
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

    private sealed class BoundedReadStream(Stream inner, int maxBytes) : Stream
    {
        private long _read;

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => _read;
            set => throw new NotSupportedException();
        }

        public override void Flush() => throw new NotSupportedException();

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = inner.Read(buffer, offset, count);
            Count(n);
            return n;
        }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var n = await inner.ReadAsync(buffer, cancellationToken);
            Count(n);
            return n;
        }

        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            var n = await inner.ReadAsync(buffer.AsMemory(offset, count), cancellationToken);
            Count(n);
            return n;
        }

        private void Count(int n)
        {
            _read += n;
            if (_read > maxBytes) throw new ChatGptException("ChatGPT 的回复超过大小限制，没有保存。");
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }

        public override async ValueTask DisposeAsync()
        {
            await inner.DisposeAsync();
            await base.DisposeAsync();
        }
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
            new("ChatGPT 不接受墨水瓶这次的调用方式，请重新登录；若仍不行，请改用 API Key。", signInAgain: true),
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
