using System.Net;
using System.Text;
using System.Text.Json;
using Zhupi.Core.Teacher;

namespace Zhupi.Core.Tests;

public sealed class FollowUpTest : IDisposable
{
    private const string Essay = "People enjoys night markets. The city feels more warm.";

    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-followup-");
    private readonly Notebook _book;
    private readonly Piece _page;
    private readonly StoredReview _review;

    public FollowUpTest()
    {
        _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), new TickingClock());
        var page = _book.NewPage(PieceKind.Essay);
        _book.Write(page.Id, "夜市", "Write about night markets.", Essay);
        _page = _book.HandIn(page.Id);
        _review = _book.SaveReview(_page.Id, MarkingProtocol.Read("""
            {"band": 8, "verdict": "语言错误较多。", "marks": [
              {"quote": "People enjoys", "fix": "People enjoy", "why": "主语复数", "severity": "must", "kind": "语法"},
              {"quote": "more warm", "fix": "warmer", "why": "比较级", "severity": "maybe", "kind": "用词"}]}
            """), "t");
    }

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public void MaterialCarriesThePageTheReviewTheMarkAndTheThread()
    {
        var earlier = new[] { new Talk(2, "为什么？", "因为 warm 是单音节词。", "t", DateTimeOffset.UnixEpoch) };

        var material = FollowUpProtocol.Material(_page, _review, 2, earlier, "  还有别的说法吗？ ");

        Assert.Contains("题目：\n<<<\nWrite about night markets.\n>>>", material);
        Assert.Contains("学生作文：\n<<<\n" + Essay + "\n>>>", material);
        Assert.Contains("1. 必改「People enjoys」→「People enjoy」：主语复数", material);
        Assert.Contains("2. 可改「more warm」→「warmer」：比较级", material);
        Assert.Contains("学生问的是第 2 处批注：「more warm」→「warmer」。", material);
        Assert.Contains("学生：为什么？\n老师：因为 warm 是单音节词。", material);
        Assert.EndsWith("学生的新问题：\n<<<\n还有别的说法吗？\n>>>", material);
        Assert.Contains("六级作文", FollowUpProtocol.Instruction(PieceKind.Essay));
        Assert.Contains("六级汉译英", FollowUpProtocol.Instruction(PieceKind.Translation));
    }

    [Fact]
    public void LongHistoryKeepsTheNewestExchanges()
    {
        var talks = Enumerable.Range(1, 10)
            .Select(i => new Talk(null, $"问题{i}", new string('答', 1000) + i, "t", DateTimeOffset.UnixEpoch)).ToList();

        var material = FollowUpProtocol.Material(_page, _review, null, talks, "新问题");

        Assert.DoesNotContain("问题1\n", material);
        Assert.Contains("问题10", material);
        Assert.DoesNotContain("学生问的是第", material);
    }

    [Fact]
    public void QuestionsMustBeNonEmptyAndShort()
    {
        Assert.Equal("为什么", FollowUpProtocol.CheckedQuestion("  为什么 "));
        Assert.Throws<TeacherException>(() => FollowUpProtocol.CheckedQuestion("   "));
        Assert.Throws<TeacherException>(() => FollowUpProtocol.CheckedQuestion(new string('问', 1001)));
    }

    [Fact]
    public void TalksAreKeptPerThread()
    {
        _book.SaveTalk(_review.Id, 1, "为什么不加 s？", "people 是复数。", "t");
        _book.SaveTalk(_review.Id, null, "整篇怎样提高？", "多用具体例子。", "t");
        _book.SaveTalk(_review.Id, 1, "还有类似的错吗？", "没有了。", "t");

        Assert.Equal(["为什么不加 s？", "还有类似的错吗？"], _book.Talks(_review.Id, 1).Select(t => t.Question));
        Assert.Equal(["整篇怎样提高？"], _book.Talks(_review.Id, null).Select(t => t.Question));
        Assert.Empty(_book.Talks(_review.Id, 2));
    }

    [Fact]
    public void NothingIsKeptWithoutAnAnswerOrForAMissingMark()
    {
        Assert.Throws<NotebookException>(() => _book.SaveTalk(_review.Id, 1, "q", "  ", "t"));
        Assert.Throws<NotebookException>(() => _book.SaveTalk(_review.Id, 9, "q", "a", "t"));
        Assert.Throws<NotebookException>(() => _book.SaveTalk("no-such-review", null, "q", "a", "t"));
        Assert.Empty(_book.Talks(_review.Id, 1));
    }

    [Fact]
    public async Task PlainAnswersDoNotAskForJson()
    {
        string? sent = null;
        var http = new HttpClient(new Capture(body => sent = body));
        var teacher = new ChatTeacher(http, new TeacherConnection("https://api.example.test", "sk", "m"));

        Assert.Equal("回答", await teacher.AskAsync("i", "m", jsonReply: false, CancellationToken.None));

        using var json = JsonDocument.Parse(sent!);
        Assert.False(json.RootElement.TryGetProperty("response_format", out _));
    }

    private sealed class Capture(Action<string> seen) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            seen(await request.Content!.ReadAsStringAsync(cancellationToken));
            var reply = JsonSerializer.Serialize(new { choices = new[] { new { message = new { content = "回答" }, finish_reason = "stop" } } });
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(reply, Encoding.UTF8, "application/json") };
        }
    }
}
