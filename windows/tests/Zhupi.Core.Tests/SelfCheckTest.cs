namespace Zhupi.Core.Tests;

public sealed class SelfCheckTest
{
    private const string Task =
        "Directions: Write an essay that begins with the sentence “Reading classics is never out of date.” " +
        "You should write at least 150 words but no more than 200 words.";

    [Fact]
    public void ReadsTheWordRangeFromTheTask()
    {
        Assert.Equal(new WordTarget(150, 200, true), SelfCheck.EssayTarget(Task));
        Assert.Equal(new WordTarget(120, 180, true), SelfCheck.EssayTarget("请写 120–180 词的短文"));
        Assert.Equal(SelfCheck.UsualEssayTarget, SelfCheck.EssayTarget("Write about your hometown."));
    }

    [Fact]
    public void FindsTheRequiredOpeningSentence()
    {
        Assert.Equal("Reading classics is never out of date.", SelfCheck.RequiredOpening(Task));
        Assert.Null(SelfCheck.RequiredOpening("Write about your hometown."));
    }

    [Fact]
    public void EssayChecksCountWordsOpeningAndParagraphs()
    {
        var text = "Reading classics is never out of date. They teach patience.\nSecond paragraph here.";

        var lines = SelfCheck.Essay(Task, text);

        Assert.Equal(
        [
            new CheckLine("字数 13，还差 137 个词（150–200）", CheckState.Todo),
            new CheckLine("用了给定的开头句", CheckState.Done),
            new CheckLine("现在 2 段，一般写三段：观点、展开、结论", CheckState.Todo),
        ], lines);
    }

    [Fact]
    public void OpeningMatchIgnoresQuotesCaseAndSpacing()
    {
        var lines = SelfCheck.Essay(Task, "  reading   classics is never out of date. More.");

        Assert.Contains(new CheckLine("用了给定的开头句", CheckState.Done), lines);
        Assert.Contains(new CheckLine("还没用给定的开头句开头", CheckState.Todo), SelfCheck.Essay(Task, "Classics matter."));
    }

    [Fact]
    public void TooManyWordsIsFlagged()
    {
        var text = string.Join(' ', Enumerable.Repeat("word", 210));

        Assert.Equal(new CheckLine("字数 210，多了 10 个词（150–200）", CheckState.Todo), SelfCheck.Essay(Task, text)[0]);
    }

    [Fact]
    public void TranslationComparesSentenceCounts()
    {
        const string source = "春节是中国最重要的传统节日。每到春节，人们都会回家。除夕夜全家团聚！";

        Assert.Equal(new CheckLine("原文 3 句，译文才 1 句，看看有没有漏译", CheckState.Todo),
            SelfCheck.Translation(source, "The Spring Festival is the most important festival.")[0]);
        Assert.Equal(new CheckLine("原文 3 句，译文写了 3 句", CheckState.Done),
            SelfCheck.Translation(source, "It matters. People go home. Families gather!")[0]);
        Assert.Equal([new CheckLine("先把中文原文放进来", CheckState.Todo)], SelfCheck.Translation("", "x"));
    }
}
