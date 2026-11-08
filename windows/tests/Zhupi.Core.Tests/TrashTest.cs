using Zhupi.Core.Teacher;

namespace Zhupi.Core.Tests;

public sealed class TrashTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("zhupi-trash-");
    private readonly Notebook _book;

    public TrashTest() => _book = Notebook.Open(Path.Combine(_dir.FullName, "study.db"), new TickingClock());

    public void Dispose()
    {
        _book.Dispose();
        _dir.Delete(recursive: true);
    }

    private Piece Reviewed(string title)
    {
        var page = _book.NewPage(PieceKind.Essay);
        _book.Write(page.Id, title, "Write about night markets.", "People enjoys night markets.");
        page = _book.HandIn(page.Id);
        var review = _book.SaveReview(page.Id, MarkingProtocol.Read("""
            {"band": 8, "verdict": "还行。", "marks": [
              {"quote": "People enjoys", "fix": "People enjoy", "why": "主语复数", "severity": "must", "kind": "语法"}]}
            """), "t");
        _book.SaveTalk(review.Id, 1, "为什么？", "people 是复数。", "t");
        return page;
    }

    [Fact]
    public void ADiscardedPageLeavesTheContentsAndTheLookBackUntilRestored()
    {
        var kept = Reviewed("留着");
        var thrown = Reviewed("扔掉");

        _book.Discard(thrown.Id);

        Assert.Equal([kept.Id], _book.Contents(PieceKind.Essay).Select(p => p.Id));
        Assert.Equal(1, _book.Count(PieceKind.Essay));
        Assert.Equal([thrown.Id], _book.Trash(PieceKind.Essay).Select(p => p.Id));
        var look = _book.LookBack();
        Assert.Equal(1, look.EssaysHandedIn);
        Assert.Single(look.Bands);
        Assert.Equal(1, look.Habits.Single().Count);
        Assert.Equal("扔掉", _book.Get(thrown.Id).Title);

        _book.Restore(thrown.Id);

        Assert.Equal(2, _book.Count(PieceKind.Essay));
        Assert.Empty(_book.Trash(PieceKind.Essay));
        Assert.Equal(2, _book.LookBack().Habits.Single().Count);
    }

    [Fact]
    public void OnlyPagesInTheTrashCanBePurged()
    {
        var page = Reviewed("还在目录里");

        Assert.Equal("只有废纸篓里的页才能彻底删除。", Assert.Throws<NotebookException>(() => _book.Purge(page.Id)).Message);
        Assert.NotNull(_book.LatestReview(page.Id));
    }

    [Fact]
    public void PurgingRemovesThePageAndWhatHangsOffItButKeepsWordsAndRewrites()
    {
        var page = Reviewed("要彻底删的");
        var review = _book.LatestReview(page.Id)!;
        var rewrite = _book.Rewrite(page.Id);
        var word = _book.AddWord("people enjoys", "people enjoy", "", page.Id);

        _book.Discard(page.Id);
        _book.Purge(page.Id);

        Assert.Throws<NotebookException>(() => _book.Get(page.Id));
        Assert.Empty(_book.Trash(PieceKind.Essay));
        Assert.Empty(_book.Talks(review.Id, 1));
        Assert.Null(_book.Get(rewrite.Id).Revises);
        Assert.Null(_book.Words().Single(w => w.Id == word.Id).SourcePieceId);
        Assert.Equal(0, _book.LookBack().EssaysHandedIn);
    }

    [Fact]
    public void ADiscardedDraftIsNotReopenedFromItsPaper()
    {
        _book.ImportPapers(ExamPackFile.Parse("""
            {"format":"CET6ExamPack","version":1,"papers":[{"stableId":"cet6-2030-06-15-1","examDate":"2030-06-15","setCode":"1",
             "revision":1,"sourceName":"测试","licenseStatus":"original","reviewed":true,"writingPrompt":"Write.","chineseText":"中文。"}]}
            """u8.ToArray()));
        var draft = _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation);

        _book.Discard(draft.Id);

        Assert.NotEqual(draft.Id, _book.StartFromPaper("cet6-2030-06-15-1", PieceKind.Translation).Id);
        Assert.Equal(1, _book.Shelf().Single().Translation.Pages);
    }
}
