using System.IO;
using System.Windows.Threading;
using Inkwell.App.ViewModels;
using Inkwell.Core;
using Inkwell.Core.Teacher;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.Tests;

public sealed class WritingFlowTest : IDisposable
{
    private readonly DirectoryInfo _directory = Directory.CreateTempSubdirectory("inkwell-writing-");
    private string DataFile => Path.Combine(_directory.FullName, "study.db");

    [Fact]
    public Task ATranslationCanBeHandedInAfterItsHintSettles() => WithNotebook(book =>
    {
        var model = View(book);
        model.ShowTabCommand.Execute(Book.Translations);
        model.NewPageCommand.Execute(null);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        draft.Task = "我每天读书。";
        draft.Text = "I read every day.";
        var confirmations = 0;
        model.Confirm = _ =>
        {
            confirmations++;
            return true;
        };
        var handIn = draft.HandInOrRewriteCommand;
        var availability = new List<bool>();
        handIn.CanExecuteChanged += (_, _) => availability.Add(handIn.CanExecute(null));

        draft.IsHinting = true;
        var canHandInWhileWaiting = handIn.CanExecute(null);
        handIn.Execute(null);

        Assert.False(book.Get(draft.Id).IsHandedIn);
        Assert.False(canHandInWhileWaiting);
        Assert.Same(draft, model.Page);
        Assert.Equal(0, confirmations);

        draft.IsHinting = false;
        Assert.Equal([false, true], availability);
        Assert.True(handIn.CanExecute(null));
        handIn.Execute(null);

        Assert.Equal(1, confirmations);
        Assert.True(book.Get(draft.Id).IsHandedIn);
        var handedIn = Assert.IsType<PageViewModel>(model.Page);
        Assert.Equal(draft.Id, handedIn.Id);
        Assert.True(handedIn.IsHandedIn);
    });

    [Fact]
    public Task FailedSaveKeepsTheDraftOpenUntilRetrySucceeds() => WithNotebook(book =>
    {
        var older = book.NewPage(PieceKind.Essay);
        var latest = book.Write(book.NewPage(PieceKind.Essay).Id, "", "Write a draft.", "Original text.");
        var model = View(book);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        var other = model.Contents.Single(line => line.Id == older.Id);
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = DataFile, Pooling = false }.ToString());
        connection.Open();
        using var sql = connection.CreateCommand();
        sql.CommandText = """
            CREATE TRIGGER reject_draft_save BEFORE UPDATE OF title, task, text ON pieces
            BEGIN SELECT RAISE(FAIL, 'test write failure'); END;
            """;
        sql.ExecuteNonQuery();

        draft.Text = "A newer draft.";
        model.Selected = other;
        model.ShowTabCommand.Execute(Book.Translations);

        Assert.Same(draft, model.Page);
        Assert.Equal(latest.Id, model.Selected?.Id);
        Assert.Equal(Book.Essays, model.Tab);
        Assert.True(draft.HasSaveError);
        Assert.Equal("A newer draft.", draft.Text);
        Assert.Equal("Original text.", book.Get(latest.Id).Text);

        sql.CommandText = "DROP TRIGGER reject_draft_save;";
        sql.ExecuteNonQuery();
        model.Selected = other;

        Assert.Equal(older.Id, model.Page?.Id);
        Assert.Equal("A newer draft.", book.Get(latest.Id).Text);
    });

    [Fact]
    public Task FilteringKeepsTheOpenDraftUntilAResultIsChosen() => WithNotebook(book =>
    {
        var match = book.Write(book.NewPage(PieceKind.Essay).Id, "Campus life", "", "");
        var latest = book.NewPage(PieceKind.Essay);
        var model = View(book);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        draft.Text = "A draft still being edited.";

        model.Search = "campus";

        Assert.Same(draft, model.Page);
        Assert.Null(model.Selected);
        Assert.Equal("A draft still being edited.", draft.Text);
        var line = Assert.Single(model.Contents);
        Assert.Equal(match.Id, line.Id);

        model.Selected = line;

        Assert.Equal(match.Id, model.Page?.Id);
        Assert.Equal("A draft still being edited.", book.Get(latest.Id).Text);
    });

    [Fact]
    public Task RewritingFromFilteredTrashOpensANewDraftInTheContents() => WithNotebook(book =>
    {
        var essay = book.Write(book.NewPage(PieceKind.Essay).Id, "Campus life", "Describe campus life.", "I walk to class.");
        book.HandIn(essay.Id);
        book.Discard(essay.Id);
        var model = View(book);
        model.ToggleTrashCommand.Execute(null);
        model.Search = "campus";
        var original = Assert.IsType<PageViewModel>(model.Page);

        original.HandInOrRewriteCommand.Execute(null);

        Assert.False(model.ShowingTrash);
        Assert.Empty(model.Search);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        Assert.True(draft.IsDraft);
        Assert.NotEqual(essay.Id, draft.Id);
        Assert.Equal(draft.Id, model.Selected?.Id);
        Assert.Equal(essay.Id, book.Get(draft.Id).Revises);
        Assert.Equal(essay.Id, Assert.Single(book.Trash(PieceKind.Essay)).Id);
    });

    [Fact]
    public Task ExportUsesTheEditorsCurrentTextWithoutSavingTheDraft() => WithNotebook(book =>
    {
        var page = book.Write(book.NewPage(PieceKind.Essay).Id, "原题", "Old task.", "Saved text.");
        var model = View(book);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        draft.Title = "读书计划";
        draft.Task = "Write about reading.";
        draft.Text = "New text still in the editor.";
        var path = Path.Combine(_directory.FullName, draft.MarkdownFileName);

        draft.ExportMarkdown(path);

        var markdown = File.ReadAllText(path);
        Assert.Equal("作文-01.md", Path.GetFileName(path));
        Assert.Contains("# 读书计划", markdown);
        Assert.Contains("Write about reading.", markdown);
        Assert.Contains("New text still in the editor.", markdown);
        Assert.DoesNotContain("Saved text.", markdown);
        Assert.Equal(page, book.Get(page.Id));
        Assert.Contains("已导出", model.Message);
    });

    [Fact]
    public Task ExportIncludesOnlyTheLatestReviewAndCurrentDecisions() => WithNotebook(book =>
    {
        var page = book.Write(book.NewPage(PieceKind.Translation).Id, "读书", "我每天读书。", "I reads every day.");
        book.HandIn(page.Id);
        book.SaveReview(page.Id, new Marking(5, "较早的总评", [], []), "测试老师");
        book.SaveReview(page.Id, new Marking(8, "当前总评", [new Dimension("语法", "一般")],
            [
                new Mark(1, "I reads", "I read", "主谓一致。", Severity.Must, "语法"),
                new Mark(2, "not in the text", "another phrase", "可以写得更简洁。", Severity.Maybe, "用词"),
            ]), "测试老师");
        var model = View(book);
        model.ShowTabCommand.Execute(Book.Translations);
        var submitted = Assert.IsType<PageViewModel>(model.Page);
        submitted.Marks[0].TakeCommand.Execute(null);
        submitted.Marks[1].LeaveCommand.Execute(null);
        var path = Path.Combine(_directory.FullName, submitted.MarkdownFileName);
        File.WriteAllText(path, "上一版文件");

        submitted.ExportMarkdown(path);

        var markdown = File.ReadAllText(path);
        Assert.Equal("翻译-01.md", Path.GetFileName(path));
        Assert.Contains("## 中文原文", markdown);
        Assert.Contains("## 译文", markdown);
        Assert.Contains("模拟档位：8", markdown);
        Assert.Contains("总评：当前总评", markdown);
        Assert.Contains("- 语法：一般", markdown);
        Assert.Contains("### 批注 1 · 必改 · 语法 · 已采纳", markdown);
        Assert.Contains("原句：I reads", markdown);
        Assert.Contains("改法：I read", markdown);
        Assert.Contains("说明：主谓一致。", markdown);
        Assert.Contains("### 批注 2 · 可改 · 用词 · 不改 · 未定位", markdown);
        Assert.DoesNotContain("较早的总评", markdown);
        Assert.DoesNotContain("上一版文件", markdown);
    });

    [Fact]
    public Task ExportKeepsAnExistingFileIfReplacementFails() => WithNotebook(book =>
    {
        book.NewPage(PieceKind.Essay);
        var model = View(book);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        var path = Path.Combine(_directory.FullName, "previous.md");
        File.WriteAllText(path, "原有内容");

        using var locked = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        draft.ExportMarkdown(path);

        Assert.Equal("原有内容", File.ReadAllText(path));
        Assert.StartsWith("导出失败", model.Message);
        Assert.Empty(_directory.GetFiles("*.tmp"));
    });

    [Fact]
    public Task ExportRequiresAMarkdownFileName() => WithNotebook(book =>
    {
        book.NewPage(PieceKind.Essay);
        var model = View(book);
        var draft = Assert.IsType<PageViewModel>(model.Page);
        var path = Path.Combine(_directory.FullName, "notes.txt");
        File.WriteAllText(path, "原有内容");

        draft.ExportMarkdown(path);

        Assert.Equal("原有内容", File.ReadAllText(path));
        Assert.Contains(".md", model.Message);
    });

    [Fact]
    public Task RevH07_IsAskingHidesMarksAndThreadAndGuardsOpenThread() => WithNotebookAsync(async book =>
    {
        var page = book.Write(book.NewPage(PieceKind.Essay).Id, "Title", "Write.", "I goes to school.");
        book.HandIn(page.Id);
        book.SaveReview(page.Id, new Marking(8, "Good", [],
            [new Mark(1, "I goes", "I go", "Agreement", Severity.Must, "Grammar")]), "Test");

        var gate = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var settings = new TeacherSettings(_directory.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => gate.Task),
        };
        var model = new MainViewModel(book, DataFile, settings);
        var vm = Assert.IsType<PageViewModel>(model.Page);
        vm.OpenPageThreadCommand.Execute(null);
        Assert.True(vm.ShowThread);
        Assert.False(vm.ShowMarks);

        var askTask = vm.AskTeacherCommand.ExecuteAsync(null);
        await Task.Delay(20);

        Assert.True(vm.IsAsking);
        Assert.False(vm.ShowMarks);
        Assert.False(vm.ShowThread);
        Assert.False(vm.ShowDraftMargin);
        Assert.False(vm.OpenPageThreadCommand.CanExecute(null));

        // Direct invocation while IsAsking must not open thread.
        vm.Marks[0].AskCommand.Execute(null);
        Assert.False(vm.ShowThread);

        // Cancel preserves review and re-shows thread when settled.
        vm.StopAskingCommand.Execute(null);
        await askTask;
        Assert.False(vm.IsAsking);
        Assert.True(vm.ShowThread);
        Assert.True(vm.OpenPageThreadCommand.CanExecute(null));
    });

    [Fact]
    public Task RevH06_OpenFromPaperAndTabSwitchRefreshUntouchedDraftAndReference() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-1", new DateOnly(2025, 12, 1), 1, 1,
            "TestSource", "", "personal-use", true,
            "Old essay task", "", "旧译文题。", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var started = book.StartFromPaper("cet6-2025-12-1", PieceKind.Essay);

        var model = View(book);
        Assert.Equal(started.Id, model.Page?.Id);
        Assert.Equal("Old essay task", model.Page?.Task);
        Assert.False(model.Page?.HasReference);

        // Update paper with new task and reference answer while on Papers tab.
        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-1", new DateOnly(2025, 12, 1), 1, 2,
            "TestSource", "", "personal-use", true,
            "Updated essay task", "", "新译文题。", "Sample trans", "Official", "Sample essay", "Official")], []);
        book.ImportPapers(pack2);

        model.ShowTabCommand.Execute(Book.Papers);
        model.Papers.Selected = model.Papers.Lines.Single();
        // StartEssay with piece.Id == Page?.Id still refreshes untouched draft Task!
        model.Papers.StartEssayCommand.Execute(null);

        Assert.Equal(Book.Essays, model.Tab);
        Assert.Equal(started.Id, model.Page?.Id);
        Assert.Equal("Updated essay task", model.Page?.Task);

        // Now hand in and return via direct tab switch from Papers after a paper reference update.
        model.Confirm = _ => true;
        model.Page!.Text = "Some essay text.";
        model.Page.HandInOrRewriteCommand.Execute(null);
        Assert.True(model.Page.HasReference);
        Assert.Equal("Sample essay", model.Page.Reference);

        var pack3 = new ExamPack([new ExamPaper(
            "cet6-2025-12-1", new DateOnly(2025, 12, 1), 1, 3,
            "TestSource", "", "personal-use", true,
            "Updated essay task", "", "新译文题。", "Sample trans", "Official", "Revised sample essay", "NewSource")], []);
        book.ImportPapers(pack3);
        model.ShowTabCommand.Execute(Book.Papers);
        model.ShowTabCommand.Execute(Book.Essays);

        Assert.Equal("Revised sample essay", model.Page.Reference);
        Assert.Contains("NewSource", model.Page.ReferenceSource);
    });

    [Fact]
    public Task RevH06_DetourThroughAnotherTabAfterPapersRefreshesTaskAndReference() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-2", new DateOnly(2025, 12, 2), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "Trans ref v1", "Src1", "Essay ref v1", "Src1")], []);
        book.ImportPapers(pack1);
        var started = book.StartFromPaper("cet6-2025-12-2", PieceKind.Essay);
        book.Write(started.Id, started.Title, started.Task, "Initial handed-in body.");
        book.HandIn(started.Id);

        var model = View(book);
        Assert.Equal("Essay ref v1", model.Page?.Reference);

        // Update pack while visiting Papers, then detour via Words before returning to Essays.
        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-2", new DateOnly(2025, 12, 2), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "Trans ref v2", "Src2", "Essay ref v2", "Src2")], []);
        book.ImportPapers(pack2);
        model.ShowTabCommand.Execute(Book.Papers);
        model.ShowTabCommand.Execute(Book.Words);
        model.ShowTabCommand.Execute(Book.Essays);

        Assert.Equal(started.Id, model.Page?.Id);
        Assert.Equal("Essay ref v2", model.Page?.Reference);
        Assert.Contains("Src2", model.Page?.ReferenceSource);

        // Now start an untouched draft, update pack to v3, detour via Review, and verify Task refreshes.
        model.ShowTabCommand.Execute(Book.Papers);
        model.Papers.Selected = model.Papers.Lines.Single();
        model.Papers.StartEssayCommand.Execute(null);
        Assert.Equal("Essay v2", model.Page?.Task);

        var pack3 = new ExamPack([new ExamPaper(
            "cet6-2025-12-2", new DateOnly(2025, 12, 2), 1, 3,
            "TestSource", "", "personal-use", true,
            "Essay v3", "", "译文 v3", "Trans ref v3", "Src3", "Essay ref v3", "Src3")], []);
        book.ImportPapers(pack3);
        model.ShowTabCommand.Execute(Book.Papers);
        model.ShowTabCommand.Execute(Book.Review);
        model.ShowTabCommand.Execute(Book.Essays);

        Assert.Equal("Essay v3", model.Page?.Task);

        // Once the draft is touched, detouring after another update must preserve the learner's draft.
        model.Page!.Text = "My touched draft.";
        var pack4 = new ExamPack([new ExamPaper(
            "cet6-2025-12-2", new DateOnly(2025, 12, 2), 1, 4,
            "TestSource", "", "personal-use", true,
            "Essay v4", "", "译文 v4", "Trans ref v4", "Src4", "Essay ref v4", "Src4")], []);
        book.ImportPapers(pack4);
        model.ShowTabCommand.Execute(Book.Papers);
        model.ShowTabCommand.Execute(Book.Settings);
        model.ShowTabCommand.Execute(Book.Essays);

        Assert.Equal("Essay v3", model.Page.Task);
        Assert.Equal("My touched draft.", model.Page.Text);
    });

    [Fact]
    public Task RevH06_DetourThroughStartTranslationAfterPapersRefreshesUntouchedEssayTask() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-3", new DateOnly(2025, 12, 3), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var essayDraft = book.StartFromPaper("cet6-2025-12-3", PieceKind.Essay);

        var model = View(book);
        Assert.Equal(essayDraft.Id, model.Page?.Id);
        Assert.Equal("Essay v1", model.Page?.Task);

        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-3", new DateOnly(2025, 12, 3), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "", "", "", "")], []);
        book.ImportPapers(pack2);

        model.ShowTabCommand.Execute(Book.Papers);
        model.Papers.Selected = model.Papers.Lines.Single();
        model.Papers.StartTranslationCommand.Execute(null);
        Assert.Equal(Book.Translations, model.Tab);
        Assert.Equal("译文 v2", model.Page?.Task);

        model.ShowTabCommand.Execute(Book.Essays);
        Assert.Equal(Book.Essays, model.Tab);
        Assert.Equal(essayDraft.Id, model.Page?.Id);
        Assert.Equal("Essay v2", model.Page?.Task);
    });

    [Fact]
    public Task RevH06_TocSelectionRefreshesUntouchedEssayDraftAfterPackUpdate() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-4", new DateOnly(2025, 12, 4), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var e1 = book.StartFromPaper("cet6-2025-12-4", PieceKind.Essay);
        var e2 = book.NewPage(PieceKind.Essay);

        var model = View(book);
        Assert.Equal(e2.Id, model.Page?.Id);

        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-4", new DateOnly(2025, 12, 4), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "", "", "", "")], []);
        book.ImportPapers(pack2);

        model.ShowTabCommand.Execute(Book.Papers);
        model.ShowTabCommand.Execute(Book.Essays);
        Assert.Equal(e2.Id, model.Page?.Id);

        model.Selected = model.Contents.Single(line => line.Id == e1.Id);
        Assert.Equal(e1.Id, model.Page?.Id);
        Assert.Equal("Essay v2", model.Page?.Task);
        Assert.Equal("Essay v2", book.Get(e1.Id).Task);
    });

    [Fact]
    public Task RevH06_WaitingTranslationReopenRefreshesTaskAndSentencesAndSettlesAskWords() => WithNotebookAsync(async book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-5", new DateOnly(2025, 12, 5), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "第一句。", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var t1 = book.StartFromPaper("cet6-2025-12-5", PieceKind.Translation);

        var gate = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var settings = new TeacherSettings(_directory.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => gate.Task),
        };
        var model = new MainViewModel(book, DataFile, settings);
        model.ShowTabCommand.Execute(Book.Translations);
        var vm = Assert.IsType<PageViewModel>(model.Page);
        Assert.Equal(t1.Id, vm.Id);
        vm.SelectSentenceCommand.Execute(vm.Sentences[0]);

        var askTask = vm.AskWordsCommand.ExecuteAsync(null);
        await Task.Delay(20);
        Assert.True(vm.IsHinting);
        Assert.True(vm.IsBusy);

        model.NewPageCommand.Execute(null);
        Assert.NotEqual(t1.Id, model.Page?.Id);

        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-5", new DateOnly(2025, 12, 5), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "更新第一句。第二句。", "", "", "", "")], []);
        book.ImportPapers(pack2);

        model.Selected = model.Contents.Single(line => line.Id == t1.Id);
        Assert.Same(vm, model.Page);
        Assert.Equal("更新第一句。第二句。", vm.Task);
        Assert.Equal("更新第一句。第二句。", book.Get(t1.Id).Task);
        Assert.Equal(["更新第一句。", "第二句。"], vm.Sentences.Select(s => s.Text));
        Assert.True(vm.IsHinting);
        Assert.False(vm.AskWordsCommand.CanExecute(null));

        gate.TrySetResult("词汇 → word");
        await askTask;

        Assert.False(vm.IsHinting);
        Assert.False(model.HasPendingWork);
        Assert.Contains("第 1 句的提示回来了", model.Message);
        Assert.Single(book.Hints(t1.Id, 1, "第一句。"));
        Assert.Empty(vm.SentenceHints);
    });

    [Fact]
    public Task RevH06_TrashReopenRefreshesUntouchedDraftAfterPackUpdate() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-6", new DateOnly(2025, 12, 6), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var e1 = book.StartFromPaper("cet6-2025-12-6", PieceKind.Essay);
        book.Discard(e1.Id);

        var model = View(book);
        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-6", new DateOnly(2025, 12, 6), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "", "", "", "")], []);
        book.ImportPapers(pack2);

        model.ToggleTrashCommand.Execute(null);
        Assert.True(model.ShowingTrash);
        Assert.Equal(e1.Id, model.Page?.Id);
        Assert.Equal("Essay v2", model.Page?.Task);
        Assert.Equal("Essay v2", book.Get(e1.Id).Task);
    });

    [Fact]
    public Task RevH06_StartupOpenLatestRefreshesUntouchedDraftAfterPackUpdate() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-7", new DateOnly(2025, 12, 7), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var e1 = book.StartFromPaper("cet6-2025-12-7", PieceKind.Essay);

        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-7", new DateOnly(2025, 12, 7), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "", "", "", "")], []);
        book.ImportPapers(pack2);

        var model = View(book);
        Assert.Equal(e1.Id, model.Page?.Id);
        Assert.Equal("Essay v2", model.Page?.Task);
        Assert.Equal("Essay v2", book.Get(e1.Id).Task);
    });

    [Fact]
    public Task RevH06_RefreshPaperPrefersUpdatedPaperOverStalePiece() => WithNotebook(book =>
    {
        var pack1 = new ExamPack([new ExamPaper(
            "cet6-2025-12-8", new DateOnly(2025, 12, 8), 1, 1,
            "TestSource", "", "personal-use", true,
            "Essay v1", "", "译文 v1", "", "", "", "")], []);
        book.ImportPapers(pack1);
        var e1 = book.StartFromPaper("cet6-2025-12-8", PieceKind.Essay);

        var vm = new PageViewModel(book, e1, new TeacherSettings(_directory.FullName), _ => { }, _ => false);
        var pack2 = new ExamPack([new ExamPaper(
            "cet6-2025-12-8", new DateOnly(2025, 12, 8), 1, 2,
            "TestSource", "", "personal-use", true,
            "Essay v2", "", "译文 v2", "", "", "", "")], []);
        book.ImportPapers(pack2);

        Assert.True(vm.RefreshPaper(e1));
        Assert.Equal("Essay v2", vm.Task);
        Assert.Equal("Essay v2", book.Get(e1.Id).Task);
    });

    [Fact]
    public Task PurgeAbandonsInFlightRequestAndPreventsWaitingReinsertionOrLateSave() => WithNotebookAsync(async book =>
    {
        var p1 = book.Write(book.NewPage(PieceKind.Essay).Id, "One", "Task 1", "Body 1.");
        book.HandIn(p1.Id);
        var p2 = book.Write(book.NewPage(PieceKind.Essay).Id, "Two", "Task 2", "Body 2.");
        book.Discard(p1.Id);

        var gate = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var settings = new TeacherSettings(_directory.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => gate.Task),
        };
        var model = new MainViewModel(book, DataFile, settings) { Confirm = _ => true };
        model.ToggleTrashCommand.Execute(null);
        var trashed = Assert.IsType<PageViewModel>(model.Page);
        var askTask = trashed.AskTeacherCommand.ExecuteAsync(null);
        await Task.Delay(20);

        model.PurgeCommand.Execute(model.Selected);
        Assert.True(trashed.IsAbandoned);
        Assert.False(model.HasPendingWork);

        // Even if the teacher reply arrives after abandonment, nothing is written or toasted.
        gate.TrySetResult("""{"band":8,"verdict":"late","dimensions":[],"marks":[]}""");
        await askTask;
        Assert.DoesNotContain("批完了", model.Message);
    });

    [Fact]
    public Task TranslationEditingGuardsCanHintSyncsSentencesAndSamePageSwitchSendsSay() => WithNotebookAsync(async book =>
    {
        var gate = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var settings = new TeacherSettings(_directory.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => gate.Task),
        };
        var model = new MainViewModel(book, DataFile, settings);
        model.ShowTabCommand.Execute(Book.Translations);
        model.NewPageCommand.Execute(null);
        var vm = Assert.IsType<PageViewModel>(model.Page);
        vm.Task = "第一句。第二句。";
        vm.IsEditingSource = false;

        Assert.Equal(2, vm.Sentences.Count);
        Assert.Contains("共 2 句", vm.TranslatedCount);

        vm.SelectSentenceCommand.Execute(vm.Sentences[0]);
        Assert.True(vm.AskWordsCommand.CanExecute(null));

        // IsEditingSource disables AskWords/AskFrame.
        vm.ToggleSourceEditingCommand.Execute(null);
        Assert.True(vm.IsEditingSource);
        Assert.False(vm.AskWordsCommand.CanExecute(null));
        Assert.False(vm.AskFrameCommand.CanExecute(null));

        vm.Task = "第一句。第二句。第三句。";
        Assert.Equal(3, vm.Sentences.Count);
        Assert.Contains("共 3 句", vm.TranslatedCount);
        vm.ToggleSourceEditingCommand.Execute(null);

        // Start AskWords on sentence 1, then switch to sentence 2 on the same page before reply returns.
        vm.SelectSentenceCommand.Execute(vm.Sentences[0]);
        var hintTask = vm.AskWordsCommand.ExecuteAsync(null);
        await Task.Delay(20);
        Assert.False(vm.ToggleSourceEditingCommand.CanExecute(null));

        vm.SelectSentenceCommand.Execute(vm.Sentences[1]);
        gate.TrySetResult("词组 → phrase");
        await hintTask;

        Assert.Contains("第 1 句的提示回来了", model.Message);
    });

    [Fact]
    public Task BackgroundAskTeacherRefreshesLookBackWhenOnReviewTab() => WithNotebookAsync(async book =>
    {
        var essay = book.Write(book.NewPage(PieceKind.Essay).Id, "Campus", "Write.", "I walk to class.");
        book.HandIn(essay.Id);
        var gate = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var settings = new TeacherSettings(_directory.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => gate.Task),
        };
        var model = new MainViewModel(book, DataFile, settings);
        var askTask = model.Page!.AskTeacherCommand.ExecuteAsync(null);
        await Task.Delay(20);

        model.ShowTabCommand.Execute(Book.Review);
        Assert.Empty(model.LookBack.Bars);

        gate.TrySetResult("""{"band":11,"verdict":"Solid","dimensions":[{"name":"语法","level":"好"}],"marks":[]}""");
        await askTask;

        Assert.Single(model.LookBack.Bars);
        Assert.Equal(11, model.LookBack.Bars[0].Band);
        Assert.Contains("老师批完了", model.Message);
    });

    [Fact]
    public void SplitBoundsAndBlankHandInSkipsConfirm()
    {
        var text = "Hello";
        var marks = new[]
        {
            new PlacedMark(new Mark(1, "He", "Hi", "why", Severity.Must, "k"), 0, Decision.Open),
            new PlacedMark(new Mark(2, "", "x", "y", Severity.Must, "k"), 1, Decision.Open),
            new PlacedMark(new Mark(3, "oob", "x", "y", Severity.Must, "k"), 10, Decision.Open),
            new PlacedMark(new Mark(4, "overLong", "x", "y", Severity.Must, "k"), 3, Decision.Open),
        };
        var pieces = PageViewModel.Split(text, marks);
        Assert.Equal(2, pieces.Count);
        Assert.Equal("He", pieces[0].Text);
        Assert.Equal("llo", pieces[1].Text);
    }

    [Fact]
    public Task BlankHandInSkipsConfirmAndReportsReason() => WithNotebook(book =>
    {
        book.NewPage(PieceKind.Essay);
        var confirms = 0;
        var model = View(book);
        model.Confirm = _ => { confirms++; return true; };
        var draft = Assert.IsType<PageViewModel>(model.Page);

        draft.HandInOrRewriteCommand.Execute(null);
        Assert.Equal(0, confirms);
        Assert.Contains("先写上题目", model.Message);

        draft.Task = "Write about study.";
        draft.HandInOrRewriteCommand.Execute(null);
        Assert.Equal(0, confirms);
        Assert.Contains("还没写正文", model.Message);
    });

    [Fact]
    public Task SqliteExceptionsAcrossViewModelsBecomeReadableMessages() => WithNotebook(book =>
    {
        var essay = book.Write(book.NewPage(PieceKind.Essay).Id, "T", "Task", "Body.");
        book.HandIn(essay.Id);
        book.SaveReview(essay.Id, new Marking(8, "Ok", [],
            [new Mark(1, "Body", "Bodies", "why", Severity.Must, "g")]), "Test");
        var word = book.AddWord("front", "back", "", null);

        var model = View(book);
        var page = Assert.IsType<PageViewModel>(model.Page);
        model.ShowTabCommand.Execute(Book.Words);
        model.ShowTabCommand.Execute(Book.Essays);

        using var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = DataFile, Pooling = false }.ToString());
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            CREATE TRIGGER fail_decisions BEFORE UPDATE ON marks BEGIN SELECT RAISE(FAIL, 'db err'); END;
            CREATE TRIGGER fail_words_del BEFORE DELETE ON words BEGIN SELECT RAISE(FAIL, 'db err'); END;
            """;
        cmd.ExecuteNonQuery();

        page.Marks[0].TakeCommand.Execute(null);
        Assert.Contains("SQLite", model.Message);

        model.ShowTabCommand.Execute(Book.Words);
        model.Words.RemoveCurrentCommand.Execute(null);
        Assert.Contains("SQLite", model.Words.Notice);

        cmd.CommandText = "DROP TRIGGER fail_decisions; DROP TRIGGER fail_words_del;";
        cmd.ExecuteNonQuery();
    });

    public void Dispose() => _directory.Delete(recursive: true);

    private MainViewModel View(Notebook book) => new(book, DataFile, new TeacherSettings(_directory.FullName));

    private Task WithNotebook(Action<Notebook> verify) => WithNotebookAsync(book =>
    {
        verify(book);
        return Task.CompletedTask;
    });

    private Task WithNotebookAsync(Func<Notebook, Task> verify)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            Exception? failure = null;
            dispatcher.InvokeAsync(async () =>
            {
                try
                {
                    using var book = Notebook.Open(DataFile, TimeProvider.System);
                    await verify(book);
                }
                catch (Exception error)
                {
                    failure = error;
                }
                finally
                {
                    dispatcher.BeginInvokeShutdown(DispatcherPriority.Normal);
                }
            });
            Dispatcher.Run();
            if (failure is not null) completion.SetException(failure);
            else completion.SetResult();
        })
        {
            IsBackground = true,
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    private sealed class BlockingTeacher(Func<Task<string>> reply) : ITeacher
    {
        public async Task<string> AskAsync(string instruction, string material, bool jsonReply, CancellationToken cancel)
        {
            using var reg = cancel.Register(() => { });
            var task = reply();
            var completed = await Task.WhenAny(task, Task.Delay(Timeout.Infinite, cancel));
            if (completed == task) return await task;
            cancel.ThrowIfCancellationRequested();
            return "";
        }
    }
}
