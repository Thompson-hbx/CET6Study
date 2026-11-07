using Zhupi.Core.Teacher;

namespace Zhupi.Core.Tests;

public sealed class TranslationHintTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-hints-");
    private readonly Notebook _book;

    public TranslationHintTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), new TickingClock());

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    [Fact]
    public void SplitsAtEndMarksAndKeepsClosingQuotes()
    {
        Assert.Equal(
            ["春节是中国最重要的传统节日。", "他说：“回家吧！”", "你去吗？", "最后一句没有句号"],
            SourceText.Sentences("春节是中国最重要的传统节日。他说：“回家吧！”你去吗？最后一句没有句号"));
    }

    [Fact]
    public void RemovesPdfLineBreaksButKeepsSpacesBetweenLatinWords()
    {
        Assert.Equal(["人工智能辅助诊断系统帮助医生识别 AI model 的早期症状。", "第二句。"],
            SourceText.Sentences("人工智能辅助诊断\n系统帮助医生识别 AI\nmodel 的早期\r\n症状。\n\n  第二句。"));
        Assert.Empty(SourceText.Sentences("  \n "));
    }

    [Fact]
    public void HintMaterialNumbersTheSourceAndNamesTheSentence()
    {
        var sentences = SourceText.Sentences("春节是最重要的节日。人们都会回家团聚。");

        var material = HintProtocol.Material(sentences, 2);

        Assert.Contains("[1] 春节是最重要的节日。\n[2] 人们都会回家团聚。", material);
        Assert.EndsWith("要提示的是第 2 句：\n<<<\n人们都会回家团聚。\n>>>", material);
        Assert.Throws<TeacherException>(() => HintProtocol.Material(sentences, 3));
        Assert.Contains("不要写出这一句的完整英文译文", HintProtocol.Instruction(HintKind.Words));
        Assert.Contains("骨架", HintProtocol.Instruction(HintKind.Frame));
    }

    [Fact]
    public void KeepsTheNewestHintOfEachKindForUnchangedSentences()
    {
        var page = _book.NewPage(PieceKind.Translation);
        _book.SaveHint(page.Id, 2, "人们都会回家团聚。", HintKind.Words, "团聚 → reunite", "t");
        _book.SaveHint(page.Id, 2, "人们都会回家团聚。", HintKind.Frame, "主语 people + 谓语 return", "t");
        _book.SaveHint(page.Id, 2, "人们都会回家团聚。", HintKind.Words, "团聚 → get together", "t");

        var hints = _book.Hints(page.Id, 2, "人们都会回家团聚。");

        Assert.Equal([(HintKind.Words, "团聚 → get together"), (HintKind.Frame, "主语 people + 谓语 return")],
            hints.Select(h => (h.Kind, h.Text)));
        Assert.Empty(_book.Hints(page.Id, 2, "人们都会回老家。"));
        Assert.Empty(_book.Hints(page.Id, 1, "人们都会回家团聚。"));
    }

    [Fact]
    public void HintsBelongToTranslationPagesWithAnAnswer()
    {
        var essay = _book.NewPage(PieceKind.Essay);
        var translation = _book.NewPage(PieceKind.Translation);

        Assert.Throws<NotebookException>(() => _book.SaveHint(essay.Id, 1, "句子。", HintKind.Words, "x", "t"));
        Assert.Throws<NotebookException>(() => _book.SaveHint(translation.Id, 1, "句子。", HintKind.Words, " ", "t"));
    }
}
