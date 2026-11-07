using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Zhupi.Core.ChatGpt;

/// <summary>
/// Waits on 127.0.0.1 for the browser to come back from ChatGPT sign-in. It listens before the browser is
/// opened, answers only <c>GET /auth/callback</c>, and stops after the first such request.
/// </summary>
public sealed class LoopbackCallback : IDisposable
{
    private const string CallbackPath = "/auth/callback";

    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);

    public LoopbackCallback()
    {
        _listener.Start();
        RedirectUri = new Uri($"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}{CallbackPath}");
    }

    public Uri RedirectUri { get; }

    public void Dispose() => _listener.Stop();

    /// <summary>The query parameters of the callback request.</summary>
    public async Task<IReadOnlyDictionary<string, string>> WaitAsync(CancellationToken cancel)
    {
        while (true)
        {
            using var client = await _listener.AcceptTcpClientAsync(cancel);
            await using var stream = client.GetStream();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancel);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));
            var requestLine = await ReadRequestLine(stream, timeout.Token);
            var parts = requestLine?.Split(' ');
            if (parts is not { Length: 3 } || parts[0] != "GET" || !parts[1].StartsWith(CallbackPath, StringComparison.Ordinal))
            {
                await Respond(stream, "404 Not Found", "这里没有东西。", cancel);
                continue;
            }
            var target = parts[1];
            var question = target.IndexOf('?');
            var path = question < 0 ? target : target[..question];
            if (path != CallbackPath)
            {
                await Respond(stream, "404 Not Found", "这里没有东西。", cancel);
                continue;
            }
            var query = Parse(question < 0 ? "" : target[(question + 1)..]);
            await Respond(stream, "200 OK", query.ContainsKey("error")
                ? "ChatGPT 登录没有完成。可以关掉这个页面，回到朱批再试一次。"
                : "登录信息已经交给朱批，可以关掉这个页面，回到朱批了。", cancel);
            return query;
        }
    }

    private static async Task<string?> ReadRequestLine(NetworkStream stream, CancellationToken cancel)
    {
        var buffer = new byte[8192];
        var total = 0;
        try
        {
            while (total < buffer.Length)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(total), cancel);
                if (read == 0) break;
                total += read;
                var text = Encoding.ASCII.GetString(buffer, 0, total);
                var end = text.IndexOf("\r\n", StringComparison.Ordinal);
                if (end >= 0) return text[..end];
            }
        }
        catch (OperationCanceledException)
        {
        }
        return null;
    }

    private static Dictionary<string, string> Parse(string query)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = pair.IndexOf('=');
            var name = Uri.UnescapeDataString((equals < 0 ? pair : pair[..equals]).Replace('+', ' '));
            var value = equals < 0 ? "" : Uri.UnescapeDataString(pair[(equals + 1)..].Replace('+', ' '));
            values.TryAdd(name, value);
        }
        return values;
    }

    private static async Task Respond(NetworkStream stream, string status, string message, CancellationToken cancel)
    {
        var html = $"<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>朱批</title>" +
            $"<body style=\"font-family:'Microsoft YaHei UI',sans-serif;background:#FBF8F0;color:#2B2620;padding:48px\"><p>{WebUtility.HtmlEncode(message)}</p></body></html>";
        var body = Encoding.UTF8.GetBytes(html);
        var head = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(head, cancel);
        await stream.WriteAsync(body, cancel);
    }
}
