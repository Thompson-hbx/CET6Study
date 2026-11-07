using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Zhupi.Core.Teacher;

/// <summary>A failure the learner can read; it never repeats the key or the service's response body.</summary>
public sealed class TeacherException(string message) : Exception(message);

public enum TeacherService
{
    DeepSeek,
    Compatible,
    ChatGpt,
}

/// <summary>Where the teacher lives. Not a record, so the key cannot leak through a generated ToString.</summary>
public sealed class TeacherConnection
{
    public const string DeepSeekAddress = "https://api.deepseek.com/";

    public TeacherConnection(string address, string key, string model)
    {
        Address = CheckedAddress(address);
        Key = key.Trim();
        Model = model.Trim();
        if (Key.Length == 0 || Key.Any(c => c is < '!' or > '~')) throw new TeacherException("API Key 不对：应当是一串英文字母、数字和符号。");
    }

    public Uri Address { get; }

    public string Key { get; }

    public string Model { get; }

    public override string ToString() => $"{Address} · {Model}";

    /// <summary>Only plain HTTPS base addresses: the key must never travel in clear text or to an unexpected path.</summary>
    public static Uri CheckedAddress(string value)
    {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps ||
            uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0)
        {
            throw new TeacherException("地址要以 https:// 开头，不能带账号、问号参数或 #。");
        }
        var path = uri.AbsolutePath.TrimEnd('/');
        if (path.EndsWith("/chat/completions", StringComparison.OrdinalIgnoreCase) || path.EndsWith("/models", StringComparison.OrdinalIgnoreCase))
        {
            throw new TeacherException("只填到接口的根地址，不要带 /chat/completions 或 /models。");
        }
        return new UriBuilder(uri) { Path = path + "/" }.Uri;
    }
}

/// <summary>
/// Talks to an OpenAI-style chat completions endpoint and asks for exactly one JSON object back.
/// It never retries on its own: every request may cost the learner money.
/// </summary>
public sealed class ChatTeacher(HttpClient http, TeacherConnection connection) : ITeacher
{
    /// <summary>Redirects are off so the key can never follow a redirect to another host.</summary>
    public static HttpClient NewHttpClient() => new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(20),
    })
    {
        Timeout = TimeSpan.FromMinutes(5),
    };

    public async Task<IReadOnlyList<string>> ModelsAsync(CancellationToken cancel)
    {
        using var request = Request(HttpMethod.Get, "models");
        using var response = await Send(request, cancel);
        try
        {
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
            return json.RootElement.GetProperty("data").EnumerateArray()
                .Select(m => m.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String ? id.GetString()! : "")
                .Where(id => id.Length > 0).Distinct().Order(StringComparer.Ordinal).ToList();
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            throw new TeacherException("服务返回的模型列表看不懂，可以手动填写模型名。");
        }
    }

    public async Task<string> AskAsync(string instruction, string material, bool jsonReply, CancellationToken cancel)
    {
        if (connection.Model.Length == 0) throw new TeacherException("先在设置里填好模型名。");
        var body = new JsonObject
        {
            ["model"] = connection.Model,
            ["messages"] = new JsonArray(
                new JsonObject { ["role"] = "system", ["content"] = instruction },
                new JsonObject { ["role"] = "user", ["content"] = material }),
            ["stream"] = false,
        };
        if (jsonReply) body["response_format"] = new JsonObject { ["type"] = "json_object" };
        using var request = Request(HttpMethod.Post, "chat/completions");
        request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await Send(request, cancel);
        try
        {
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
            var choice = json.RootElement.GetProperty("choices")[0];
            var finish = choice.TryGetProperty("finish_reason", out var f) && f.ValueKind == JsonValueKind.String ? f.GetString() : null;
            if (finish == "length") throw new TeacherException("老师写到一半被截断了，没有保存；可以换个模型或缩短文字再请一次。");
            if (finish is not ("stop" or null)) throw new TeacherException("服务没有正常写完这次批改，没有保存。");
            var content = choice.GetProperty("message").GetProperty("content");
            var text = content.ValueKind == JsonValueKind.String ? content.GetString() : null;
            return string.IsNullOrWhiteSpace(text) ? throw new TeacherException("老师交了白卷，没有保存。") : text;
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or IndexOutOfRangeException)
        {
            throw new TeacherException("服务的回复格式看不懂，没有保存。请检查地址是否是兼容 OpenAI 的接口。");
        }
    }

    private HttpRequestMessage Request(HttpMethod method, string path)
    {
        var request = new HttpRequestMessage(method, new Uri(connection.Address, path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", connection.Key);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return request;
    }

    private async Task<HttpResponseMessage> Send(HttpRequestMessage request, CancellationToken cancel)
    {
        HttpResponseMessage response;
        try
        {
            response = await http.SendAsync(request, cancel);
        }
        catch (HttpRequestException)
        {
            throw new TeacherException("连不上老师：请检查网络和地址。");
        }
        catch (TaskCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new TeacherException("老师太久没回应，已经停下，没有保存。");
        }
        if (response.IsSuccessStatusCode) return response;
        var code = (int)response.StatusCode;
        response.Dispose();
        throw new TeacherException(code switch
        {
            401 => "API Key 不对或已失效（401）。",
            402 => "账户余额不足（402）。",
            403 => "这个账户没有权限，或所在地区不可用（403）。",
            404 => "地址或模型名不对（404）。",
            429 => "请求太频繁或额度用完了，稍后再试（429）。",
            >= 300 and < 400 => $"服务要求跳转到别处（{code}），为保护 Key 没有跟过去，请改用最终地址。",
            >= 500 => $"服务暂时出错（{code}），稍后再试。",
            _ => $"请求没被接受（{code}），请检查地址、模型名和账户。",
        });
    }
}
