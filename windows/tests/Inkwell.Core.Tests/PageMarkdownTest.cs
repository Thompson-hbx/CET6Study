using Inkwell.Core.Teacher;

namespace Inkwell.Core.Tests;

public sealed class PageMarkdownTest
{
    [Fact]
    public void EssayKeepsParagraphsAndEscapesMarkdownAndHtml()
    {
        var page = new Piece("essay", PieceKind.Essay, 7, "校园\r\n[读书笔记]", "# Directions\r\n1. Write about reading.",
            "Reading is helpful.\r\n\r\n<details>**example**</details>\n```\n---\n$20", null,
            DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, false);

        var markdown = PageMarkdown.Format(page, null).ReplaceLineEndings("\n");

        Assert.StartsWith("# 校园 \\[读书笔记\\]\n\n作文 · 第 07 页 · 草稿\n", markdown);
        Assert.Contains("## 题目\n\n\\# Directions  \n1\\. Write about reading.", markdown);
        Assert.Contains("## 正文\n\nReading is helpful.\n\n", markdown);
        Assert.Contains("\\<details\\>\\*\\*example\\*\\*\\</details\\>", markdown);
        Assert.Contains("\\`\\`\\`", markdown);
        Assert.Contains("\\---  \n\\$20", markdown);
        Assert.DoesNotContain("## 批改", markdown);
    }

    [Fact]
    public void BlankTranslationUsesTranslationHeadingsAndShowsItsDraftStatus()
    {
        var page = new Piece("translation", PieceKind.Translation, 2, " ", "", "", "previous",
            DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, false);

        var markdown = PageMarkdown.Format(page, null).ReplaceLineEndings("\n");

        Assert.Equal("""
            # 未命名翻译

            翻译 · 第 02 页 · 重写稿

            ## 中文原文

            （空白）

            ## 译文

            （空白）


            """.ReplaceLineEndings("\n"), markdown);
    }

    [Fact]
    public void AppendReviewFormatsBandVerdictDimensionsAndMarks()
    {
        var page = new Piece("essay", PieceKind.Essay, 3, "夜市", "Night markets", "People enjoys it.", null,
            DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, false);
        var review = new StoredReview(
            Id: "rev1",
            PieceId: page.Id,
            CreatedAt: DateTimeOffset.UnixEpoch,
            Teacher: "t",
            Band: 11,
            Verdict: "  # 整体清楚\n  注意*语法*。  ",
            Dimensions:
            [
                new Dimension("内容", "较完整"),
                new Dimension("语言 [语法]", "有小错"),
            ],
            Marks:
            [
                new PlacedMark(new Mark(1, "  People enjoys  ", "  People enjoy  ", "  主谓不一致 *1*  ", Severity.Must, "语法"), 0, Decision.Taken),
                new PlacedMark(new Mark(2, "it", "", "", Severity.Maybe, ""), null, Decision.Left),
                new PlacedMark(new Mark(3, "<tag>", "tag", "不要写标签", Severity.Maybe, "格式#1"), 5, Decision.Open),
            ]);

        var markdown = PageMarkdown.Format(page, review).ReplaceLineEndings("\n");

        Assert.Contains("作文 · 第 03 页 · 已交\n", markdown);
        Assert.Contains("""
            ## 批改

            模拟档位：11

            总评：\# 整体清楚  
            注意\*语法\*。

            - 内容：较完整
            - 语言 \[语法\]：有小错

            ### 批注 1 · 必改 · 语法 · 已采纳

            原句：People enjoys  
            改法：People enjoy  
            说明：主谓不一致 \*1\*

            ### 批注 2 · 可改 · 不改 · 未定位

            原句：it  
            改法：  
            说明：

            ### 批注 3 · 可改 · 格式\#1 · 待处理

            原句：\<tag\>  
            改法：tag  
            说明：不要写标签
            """.ReplaceLineEndings("\n"), markdown);
    }

    [Fact]
    public void AppendReviewOmitsEmptyVerdictDimensionsAndMarks()
    {
        var page = new Piece("essay", PieceKind.Essay, 1, "标题", "题目", "正文", null,
            DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, false);
        var review = new StoredReview("rev2", page.Id, DateTimeOffset.UnixEpoch, "t", 9, "   ", [], []);

        var markdown = PageMarkdown.Format(page, review).ReplaceLineEndings("\n");

        Assert.Contains("## 批改\n\n模拟档位：9\n", markdown);
        Assert.DoesNotContain("总评：", markdown);
        Assert.DoesNotContain("\n- ", markdown);
        Assert.DoesNotContain("### 批注", markdown);
    }
}
