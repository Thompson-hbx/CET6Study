using System.IO;
using Inkwell.App.ViewModels;
using Inkwell.Core;
using Microsoft.Data.Sqlite;

namespace Inkwell.App.Tests;

public sealed class PapersAndLookBackTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-papers-lookback-");
    private string DbPath => Path.Combine(_dir.FullName, "study.db");

    public void Dispose() => _dir.Delete(recursive: true);

    [Fact]
    public void PapersViewModelRefreshHandlesEmptySinglePartAndProgressStates()
    {
        using var book = Notebook.Open(DbPath, TimeProvider.System);
        var opened = new List<Piece>();
        var messages = new List<string>();
        var vm = new PapersViewModel(book, messages.Add, opened.Add);

        vm.Refresh();
        Assert.True(vm.IsEmpty);
        Assert.False(vm.HasPaper);
        Assert.Equal("还没有导入", vm.Summary);

        var essayOnly = new ExamPaper(
            "cet6-2025-06-14-1", new DateOnly(2025, 6, 14), 1, 1,
            "SourceA", "", "original", false,
            "Write an essay.", "", "", "", "", "Sample essay", "RefSrc");
        var transOnly = new ExamPaper(
            "cet6-2025-06-14-2", new DateOnly(2025, 6, 14), 2, 1,
            "SourceB", "", "personal-use", true,
            "", "", "中文翻译原文。", "Sample trans", "TransSrc", "", "");
        book.ImportPapers(new ExamPack([essayOnly, transOnly], []));

        vm.Refresh();
        Assert.False(vm.IsEmpty);
        Assert.Equal(2, vm.Lines.Count);
        Assert.Equal("共 2 套 · 作文交过 0 套 · 翻译交过 0 套", vm.Summary);

        var line1 = vm.Lines.Single(l => l.Id == essayOnly.Id);
        Assert.True(line1.Unreviewed);
        vm.Selected = line1;
        Assert.True(vm.HasEssay);
        Assert.False(vm.HasTranslation);
        Assert.True(vm.StartEssayCommand.CanExecute(null));
        Assert.False(vm.StartTranslationCommand.CanExecute(null));
        Assert.Equal("待校对", vm.ReviewNote);
        Assert.Equal("附参考范文", vm.EssayReference);
        Assert.Equal("", vm.TranslationReference);
        Assert.Equal("还没写", vm.EssayState);
        Assert.Equal("写这篇作文", vm.EssayAction);

        vm.StartEssayCommand.Execute(null);
        var startedEssay = Assert.Single(opened);
        Assert.Equal(PieceKind.Essay, startedEssay.Kind);

        vm.Refresh();
        var line1Draft = vm.Lines.Single(l => l.Id == essayOnly.Id);
        Assert.Equal("作 ✎", line1Draft.EssayMark);
        Assert.Contains("草稿在", vm.EssayState);
        Assert.Equal("接着写", vm.EssayAction);

        var line2 = vm.Lines.Single(l => l.Id == transOnly.Id);
        Assert.False(line2.Unreviewed);
        vm.Selected = line2;
        Assert.False(vm.HasEssay);
        Assert.True(vm.HasTranslation);
        Assert.False(vm.StartEssayCommand.CanExecute(null));
        Assert.True(vm.StartTranslationCommand.CanExecute(null));
        Assert.Equal("附参考译文", vm.TranslationReference);
    }

    [Fact]
    public void PapersViewModelImportReportsSkippedTruncationMissingFileAndSqliteFailure()
    {
        using var book = Notebook.Open(DbPath, TimeProvider.System);
        var messages = new List<string>();
        var vm = new PapersViewModel(book, messages.Add, _ => { });

        vm.Import(Path.Combine(_dir.FullName, "nonexistent.json"));
        Assert.Equal("找不到这个内容包文件。", messages.Last());

        var packPath = Path.Combine(_dir.FullName, "pack.json");
        var json = """
            {
              "format": "CET6ExamPack",
              "version": 1,
              "papers": [
                {
                  "stableId": "cet6-2025-06-14-1",
                  "examDate": "2025-06-14",
                  "setCode": "1",
                  "revision": 1,
                  "sourceName": "S",
                  "licenseStatus": "original",
                  "reviewed": true,
                  "writingPrompt": "Prompt",
                  "chineseText": ""
                },
                { "stableId": "bad-1" },
                { "stableId": "bad-2" },
                { "stableId": "bad-3" },
                { "stableId": "bad-4" }
              ]
            }
            """;
        File.WriteAllText(packPath, json);
        vm.Import(packPath);
        Assert.Contains("新增 1 套", messages.Last());
        Assert.Contains("跳过 4 套", messages.Last());
        Assert.Contains("……", messages.Last());

        using var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = DbPath, Pooling = false }.ToString());
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "CREATE TRIGGER fail_paper_ins BEFORE INSERT ON papers BEGIN SELECT RAISE(FAIL, 'blocked'); END;";
        cmd.ExecuteNonQuery();

        var pack2Path = Path.Combine(_dir.FullName, "pack2.json");
        File.WriteAllText(pack2Path, """
            {
              "format": "CET6ExamPack",
              "version": 1,
              "papers": [
                {
                  "stableId": "cet6-2025-12-13-1",
                  "examDate": "2025-12-13",
                  "setCode": "1",
                  "revision": 1,
                  "sourceName": "S",
                  "licenseStatus": "original",
                  "reviewed": true,
                  "writingPrompt": "Prompt 2",
                  "chineseText": ""
                }
              ]
            }
            """);
        vm.Import(pack2Path);
        Assert.Contains("SQLite", messages.Last());

        cmd.CommandText = "DROP TRIGGER fail_paper_ins;";
        cmd.ExecuteNonQuery();
    }

    [Fact]
    public void LookBackViewModelHandlesEmptyStateAndSqliteException()
    {
        using var book = Notebook.Open(DbPath, TimeProvider.System);
        var messages = new List<string>();
        var vm = new LookBackViewModel(book, messages.Add);

        vm.Refresh();
        Assert.False(vm.HasBands);
        Assert.False(vm.HasHabits);
        Assert.Equal("还没有批注。", vm.Decisions);
        Assert.Equal("单词本还是空的。", vm.WordsLine);
        Assert.NotNull(vm.Data);

        // Rename underlying table temporarily so book.LookBack() throws SqliteException, verifying catch path.
        using var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = DbPath, Pooling = false }.ToString());
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "ALTER TABLE words RENAME TO words_backup;";
        cmd.ExecuteNonQuery();

        vm.Refresh();
        Assert.Contains("SQLite", Assert.Single(messages));

        cmd.CommandText = "ALTER TABLE words_backup RENAME TO words;";
        cmd.ExecuteNonQuery();
    }
}
