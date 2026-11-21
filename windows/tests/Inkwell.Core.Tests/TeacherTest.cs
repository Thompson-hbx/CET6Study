using System.Net;
using System.Text;
using System.Text.Json;
using Inkwell.Core.Teacher;

namespace Inkwell.Core.Tests;

public sealed class TeacherTest
{
    private static readonly TeacherConnection Connection = new("https://api.example.test/v1", "sk-test", "test-model");

    private static string Completion(string content, string finish = "stop") => JsonSerializer.Serialize(new
    {
        choices = new[] { new { message = new { role = "assistant", content }, finish_reason = finish } },
    });

    [Fact]
    public async Task AsksForOneJsonObjectWithoutStreaming()
    {
        var handler = new FakeServer(_ => Ok(Completion("{\"band\":11}")));
        var teacher = new ChatTeacher(new HttpClient(handler), Connection);

        var answer = await teacher.AskAsync("instruction", "material", jsonReply: true, CancellationToken.None);

        Assert.Equal("{\"band\":11}", answer);
        var sent = Assert.Single(handler.Sent);
        Assert.Equal("https://api.example.test/v1/chat/completions", sent.Url);
        Assert.Equal("Bearer sk-test", sent.Authorization);
        using var body = JsonDocument.Parse(sent.Body);
        Assert.Equal("test-model", body.RootElement.GetProperty("model").GetString());
        Assert.False(body.RootElement.GetProperty("stream").GetBoolean());
        Assert.Equal("json_object", body.RootElement.GetProperty("response_format").GetProperty("type").GetString());
        Assert.Equal("instruction", body.RootElement.GetProperty("messages")[0].GetProperty("content").GetString());
        Assert.Equal("material", body.RootElement.GetProperty("messages")[1].GetProperty("content").GetString());
        Assert.False(body.RootElement.TryGetProperty("reasoning_effort", out _));
    }

    [Theory]
    [InlineData(Depth.Low, "low")]
    [InlineData(Depth.Medium, "medium")]
    [InlineData(Depth.High, "high")]
    [InlineData(Depth.Max, "max")]
    public async Task AChosenDepthIsSentAsReasoningEffort(Depth depth, string effort)
    {
        var handler = new FakeServer(_ => Ok(Completion("{}")));

        await new ChatTeacher(new HttpClient(handler), Connection, depth).AskAsync("i", "m", jsonReply: true, CancellationToken.None);

        using var body = JsonDocument.Parse(Assert.Single(handler.Sent).Body);
        Assert.Equal(effort, body.RootElement.GetProperty("reasoning_effort").GetString());
    }

    [Fact]
    public async Task ARefusedDepthSaysHowToGoBack()
    {
        var teacher = new ChatTeacher(new HttpClient(new FakeServer(_ => new HttpResponseMessage(HttpStatusCode.BadRequest))), Connection, Depth.Max);

        var error = await Assert.ThrowsAsync<TeacherException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Equal("请求没被接受（400）：这个模型可能不支持批改深度「最深」，到「设置」改回「默认」或换一档再试。", error.Message);
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized, "API Key 不对或已失效（401）。")]
    [InlineData(HttpStatusCode.TooManyRequests, "请求太频繁或额度用完了，稍后再试（429）。")]
    [InlineData(HttpStatusCode.Found, "服务要求跳转到别处（302），为保护 Key 没有跟过去，请改用最终地址。")]
    public async Task HttpFailuresAreExplainedWithoutTheBody(HttpStatusCode code, string message)
    {
        var handler = new FakeServer(_ => new HttpResponseMessage(code) { Content = new StringContent("secret detail sk-test") });
        var teacher = new ChatTeacher(new HttpClient(handler), Connection);

        var error = await Assert.ThrowsAsync<TeacherException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Equal(message, error.Message);
        Assert.Single(handler.Sent);
    }

    [Fact]
    public async Task ATruncatedAnswerIsRejected()
    {
        var teacher = new ChatTeacher(new HttpClient(new FakeServer(_ => Ok(Completion("{\"band\":", "length")))), Connection);

        var error = await Assert.ThrowsAsync<TeacherException>(() => teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.StartsWith("老师写到一半被截断了", error.Message);
    }

    [Fact]
    public async Task ModelsAreListedSortedAndDistinct()
    {
        var teacher = new ChatTeacher(new HttpClient(new FakeServer(_ => Ok("""{"data":[{"id":"b"},{"id":"a"},{"id":"b"},{"id":""}]}"""))), Connection);

        Assert.Equal(["a", "b"], await teacher.ModelsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task LargeResponsesAreRejectedBeforeParsing()
    {
        using var http = new HttpClient(new FakeServer(_ => Ok(new string(' ', 2 * 1024 * 1024 + 1))));
        var teacher = new ChatTeacher(http, Connection);

        var error = await Assert.ThrowsAsync<TeacherException>(() => teacher.ModelsAsync(CancellationToken.None));

        Assert.Contains("超过大小限制", error.Message);
    }

    [Fact]
    public async Task UnregisteredCharsetStillParsesInModelsAndAsk()
    {
        var handler = new FakeServer(req => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(Encoding.UTF8.GetBytes(
                req.RequestUri!.AbsolutePath.EndsWith("/models", StringComparison.Ordinal)
                    ? """{"data":[{"id":"gpt-ok"}]}"""
                    : Completion("{\"band\":11}")))
            {
                Headers = { { "Content-Type", "application/json; charset=bogus-charset" } },
            },
        });
        var teacher = new ChatTeacher(new HttpClient(handler), Connection);

        Assert.Equal(["gpt-ok"], await teacher.ModelsAsync(CancellationToken.None));
        Assert.Equal("{\"band\":11}", await teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));
    }

    [Fact]
    public async Task OperationCanceledWithoutCallerCancellationIsTimeoutAndCallerCancelPropagates()
    {
        var timeoutTeacher = new ChatTeacher(
            new HttpClient(new FakeServer(_ => throw new OperationCanceledException())),
            Connection);
        var timeoutError = await Assert.ThrowsAsync<TeacherException>(() =>
            timeoutTeacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));
        Assert.Contains("太久没回应", timeoutError.Message);

        using var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            timeoutTeacher.AskAsync("i", "m", jsonReply: true, cts.Token));
    }

    [Fact]
    public async Task MidBodyIoExceptionTurnsIntoNetworkTeacherException()
    {
        var handler = new FakeServer(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StreamContent(new BrokenReadStream()),
        });
        var teacher = new ChatTeacher(new HttpClient(handler), Connection);

        var error = await Assert.ThrowsAsync<TeacherException>(() =>
            teacher.AskAsync("i", "m", jsonReply: true, CancellationToken.None));

        Assert.Equal("连不上老师：请检查网络和地址。", error.Message);
    }

    [Theory]
    [InlineData("http://api.example.test")]
    [InlineData("https://user:pw@api.example.test")]
    [InlineData("https://api.example.test/?a=1")]
    [InlineData("https://api.example.test/v1/chat/completions")]
    [InlineData("api.example.test")]
    public void OnlyPlainHttpsAddressesAreAccepted(string address) =>
        Assert.Throws<TeacherException>(() => new TeacherConnection(address, "sk", "m"));

    [Fact]
    public void TheKeyNeverShowsInText()
    {
        var connection = new TeacherConnection("https://api.example.test", " sk-secret ", "m");

        Assert.Equal("sk-secret", connection.Key);
        Assert.DoesNotContain("sk-secret", connection.ToString());
        Assert.Throws<TeacherException>(() => new TeacherConnection("https://api.example.test", "钥匙", "m"));
    }

    private static HttpResponseMessage Ok(string json) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };

    private sealed record SentRequest(string Url, string? Authorization, string Body);

    private sealed class BrokenReadStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => throw new IOException("reset");
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            throw new IOException("reset");
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            throw new IOException("reset");
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private sealed class FakeServer(Func<HttpRequestMessage, HttpResponseMessage> answer) : HttpMessageHandler
    {
        public List<SentRequest> Sent { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            Sent.Add(new SentRequest(request.RequestUri!.ToString(), request.Headers.Authorization?.ToString(), body));
            return answer(request);
        }
    }
}
