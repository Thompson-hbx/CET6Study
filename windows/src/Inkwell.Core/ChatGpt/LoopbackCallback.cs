using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace Inkwell.Core.ChatGpt;

/// <summary>
/// Waits on 127.0.0.1 for the browser to come back from ChatGPT sign-in. It listens before the browser is
/// opened, answers only <c>GET /auth/callback</c>, and stops after the first such request with this attempt's state.
/// </summary>
public sealed class LoopbackCallback : IDisposable
{
    private const string CallbackPath = "/auth/callback";
    private const int MaxConcurrentConnections = 16;
    private const int MaxHeadBytes = 16 * 1024;
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(10);

    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);

    public LoopbackCallback()
    {
        _listener.Start();
        RedirectUri = new Uri($"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}{CallbackPath}");
    }

    public Uri RedirectUri { get; }

    public void Dispose() => _listener.Stop();

    /// <summary>
    /// The query parameters of the callback request that carries <paramref name="state"/>. Callbacks with another
    /// state are answered and ignored, so a local program racing the browser cannot end the sign-in.
    /// </summary>
    public async Task<IReadOnlyDictionary<string, string>> WaitAsync(string state, CancellationToken cancel)
    {
        cancel.ThrowIfCancellationRequested();
        using var done = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        var inFlight = new List<Task<IReadOnlyDictionary<string, string>?>>();
        Task<TcpClient>? acceptTask = null;
        try
        {
            while (true)
            {
                if (inFlight.Count >= MaxConcurrentConnections)
                {
                    var finished = await Task.WhenAny(inFlight).WaitAsync(cancel);
                    inFlight.Remove(finished);
                    if (await finished is { } hit) return hit;
                    continue;
                }

                acceptTask ??= _listener.AcceptTcpClientAsync(done.Token).AsTask();
                var waitables = new Task[inFlight.Count + 1];
                waitables[0] = acceptTask;
                for (var i = 0; i < inFlight.Count; i++) waitables[i + 1] = inFlight[i];

                var completed = await Task.WhenAny(waitables);
                if (completed == acceptTask)
                {
                    acceptTask = null;
                    var client = await (Task<TcpClient>)completed;
                    inFlight.Add(ServeSafeAsync(client, state, cancel, done.Token));
                }
                else
                {
                    var task = (Task<IReadOnlyDictionary<string, string>?>)completed;
                    inFlight.Remove(task);
                    if (await task is { } query) return query;
                }
            }
        }
        finally
        {
            done.Cancel();
            if (acceptTask is not null)
            {
                try
                {
                    (await acceptTask).Dispose();
                }
                catch
                {
                }
            }
            if (inFlight.Count > 0)
            {
                try
                {
                    await Task.WhenAll(inFlight);
                }
                catch
                {
                }
            }
        }
    }

    private async Task<IReadOnlyDictionary<string, string>?> ServeSafeAsync(
        TcpClient client, string state, CancellationToken callerCancel, CancellationToken shutdownCancel)
    {
        using (client)
        {
            try
            {
                return await Serve(client, state, callerCancel, shutdownCancel);
            }
            catch (Exception e) when (e is IOException or SocketException)
            {
                // A reset stray connection (a browser preconnect, another local program) must not end the sign-in.
                return null;
            }
            catch (OperationCanceledException) when (!callerCancel.IsCancellationRequested)
            {
                return null;
            }
        }
    }

    private async Task<IReadOnlyDictionary<string, string>?> Serve(
        TcpClient client, string state, CancellationToken callerCancel, CancellationToken shutdownCancel)
    {
        await using var stream = client.GetStream();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(shutdownCancel);
        timeout.CancelAfter(RequestTimeout);
        var head = await ReadRequestHeadAsync(stream, callerCancel, timeout.Token);
        if (head is null) return null;

        var firstLineEnd = head.IndexOf("\r\n", StringComparison.Ordinal);
        var requestLine = firstLineEnd < 0 ? head : head[..firstLineEnd];
        var headersBlock = firstLineEnd < 0 ? "" : head[(firstLineEnd + 2)..];
        var parts = requestLine.Split(' ');
        if (parts is not { Length: 3 } || !parts[2].StartsWith("HTTP/1.", StringComparison.Ordinal))
        {
            await Respond(client, stream, "400 Bad Request", "这不是有效的网页请求。", timeout.Token);
            return null;
        }
        if (!HasValidHost(headersBlock, $"127.0.0.1:{RedirectUri.Port}"))
        {
            await Respond(client, stream, "400 Bad Request", "这不是墨水瓶这次登录的回信，已经忽略。", timeout.Token);
            return null;
        }

        var target = parts[1];
        var question = target.IndexOf('?');
        var path = question < 0 ? target : target[..question];
        if (parts[0] != "GET" || path != CallbackPath)
        {
            await Respond(client, stream, "404 Not Found", "这里没有东西。", timeout.Token);
            return null;
        }
        if (!TryParse(question < 0 ? "" : target[(question + 1)..], out var query))
        {
            await Respond(client, stream, "400 Bad Request", "登录回信的格式不对，请再试一次。", timeout.Token);
            return null;
        }
        if (!query.TryGetValue("state", out var returned) || !ConstantTimeEquals(returned, state))
        {
            await Respond(client, stream, "400 Bad Request", "这不是墨水瓶这次登录的回信，已经忽略。", timeout.Token);
            return null;
        }
        try
        {
            await Respond(client, stream, "200 OK", query.ContainsKey("error")
                ? "ChatGPT 登录没有完成。可以关掉这个页面，回到墨水瓶再试一次。"
                : "登录信息已经交给墨水瓶，可以关掉这个页面，回到墨水瓶了。", timeout.Token);
        }
        catch (Exception e) when (e is IOException or SocketException)
        {
            // The code is single-use and already here; a browser that left early must not cost the sign-in.
        }
        catch (OperationCanceledException) when (!callerCancel.IsCancellationRequested)
        {
        }
        return query;
    }

    private static async Task<string?> ReadRequestHeadAsync(NetworkStream stream, CancellationToken callerCancel, CancellationToken readCancel)
    {
        var buffer = new byte[MaxHeadBytes];
        var total = 0;
        try
        {
            while (total < buffer.Length)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(total), readCancel);
                if (read == 0) return null;
                total += read;
                var end = buffer.AsSpan(0, total).IndexOf("\r\n\r\n"u8);
                if (end >= 0) return Encoding.ASCII.GetString(buffer, 0, end);
            }
        }
        catch (OperationCanceledException) when (!callerCancel.IsCancellationRequested)
        {
            return null;
        }
        return null;
    }

    private static bool HasValidHost(string headersBlock, string expectedHost)
    {
        var seen = false;
        foreach (var line in headersBlock.Split("\r\n", StringSplitOptions.RemoveEmptyEntries))
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue;
            var name = line[..colon].Trim();
            if (!string.Equals(name, "Host", StringComparison.OrdinalIgnoreCase)) continue;
            if (seen) return false;
            seen = true;
            var value = line[(colon + 1)..].Trim();
            if (!string.Equals(value, expectedHost, StringComparison.OrdinalIgnoreCase)) return false;
        }
        return seen;
    }

    private static bool TryParse(string query, out Dictionary<string, string> values)
    {
        values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = pair.IndexOf('=');
            var rawName = (equals < 0 ? pair : pair[..equals]).Replace('+', ' ');
            var rawValue = equals < 0 ? "" : pair[(equals + 1)..].Replace('+', ' ');
            if (!HasValidPercentEncoding(rawName) || !HasValidPercentEncoding(rawValue)) return false;
            string name;
            string value;
            try
            {
                name = Uri.UnescapeDataString(rawName);
                value = Uri.UnescapeDataString(rawValue);
            }
            catch (UriFormatException)
            {
                return false;
            }
            if (!values.TryAdd(name, value)) return false;
        }
        return true;
    }

    private static bool HasValidPercentEncoding(ReadOnlySpan<char> text)
    {
        for (var i = 0; i < text.Length; i++)
        {
            if (text[i] != '%') continue;
            if (i + 2 >= text.Length || !Uri.IsHexDigit(text[i + 1]) || !Uri.IsHexDigit(text[i + 2]))
            {
                return false;
            }
            i += 2;
        }
        return true;
    }

    private static bool ConstantTimeEquals(string actual, string expected) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(actual), Encoding.UTF8.GetBytes(expected));

    private static async Task Respond(TcpClient client, NetworkStream stream, string status, string message, CancellationToken cancel)
    {
        var html = $"<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>墨水瓶</title>" +
            $"<body style=\"font-family:'Microsoft YaHei UI',sans-serif;background:#FBF8F0;color:#2B2620;padding:48px\"><p>{WebUtility.HtmlEncode(message)}</p></body></html>";
        var body = Encoding.UTF8.GetBytes(html);
        var head = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(head, cancel);
        await stream.WriteAsync(body, cancel);
        await stream.FlushAsync(cancel);
        client.Client.Shutdown(SocketShutdown.Send);
    }
}
