using System.IO;
using System.Windows.Threading;
using Inkwell.App.ViewModels;
using Inkwell.Core;
using Inkwell.Core.Teacher;

namespace Inkwell.App.Tests;

public sealed class AppLifecycleTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-lifecycle-");

    public void Dispose() => _dir.Delete(recursive: true);

    [Fact]
    public void ParseDataDirHandlesEqualsAndSpaceSeparatedAndRejectsMissingValues()
    {
        Assert.Null(App.ParseDataDir([]));
        Assert.Equal(@"C:\data\one", App.ParseDataDir(["--data-dir=C:\\data\\one"]));
        Assert.Equal(@"C:\data\two", App.ParseDataDir(["--data-dir", @"C:\data\two"]));
        Assert.Throws<NotebookException>(() => App.ParseDataDir(["--data-dir="]));
        Assert.Throws<NotebookException>(() => App.ParseDataDir(["--data-dir"]));
        Assert.Throws<NotebookException>(() => App.ParseDataDir(["--data-dir", "   "]));
    }

    [Fact]
    public void AppendBoundedErrorLogRotatesWhenSizeLimitReached()
    {
        var log = Path.Combine(_dir.FullName, "errors.log");
        var ex = new InvalidOperationException("boom-" + new string('x', 150));

        App.AppendBoundedErrorLog(log, ex, maxBytes: 200);
        Assert.True(File.Exists(log));
        Assert.False(File.Exists(log + ".1"));

        App.AppendBoundedErrorLog(log, ex, maxBytes: 200);
        Assert.True(File.Exists(log + ".1"));
        Assert.True(new FileInfo(log).Length < 500);
    }

    [Fact]
    public void TeacherSettingsHandlesLockedFilesAndHasKeyForValidation()
    {
        var settingsPath = Path.Combine(_dir.FullName, "teacher.json");
        using (var lockSettings = new FileStream(settingsPath, FileMode.Create, FileAccess.ReadWrite, FileShare.None))
        {
            var loaded = TeacherSettings.Load(_dir.FullName);
            Assert.Equal(TeacherService.DeepSeek, loaded.Service);
        }

        var settings = new TeacherSettings(_dir.FullName);
        settings.Save(TeacherService.Compatible, "https://api.example.com/v1", "m1", "sk-key-1", Depth.Default);
        Assert.True(settings.HasKeyFor(TeacherService.Compatible, "https://api.example.com/v1"));
        Assert.False(settings.HasKeyFor(TeacherService.Compatible, "https://other.example.com/v1"));
        Assert.False(settings.HasKeyFor(TeacherService.DeepSeek, TeacherConnection.DeepSeekAddress));
        Assert.False(settings.HasKeyFor(TeacherService.ChatGpt, ""));

        using (var lockSettings = new FileStream(settingsPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var saveError = Assert.ThrowsAny<Exception>(() =>
                settings.Save(TeacherService.Compatible, "https://api.example.com/v1", "m2", "sk-key-2", Depth.High));
            Assert.True(saveError is IOException or UnauthorizedAccessException, $"Unexpected exception: {saveError.GetType()}");
        }
        // Rollback restored the original key when teacher.json write failed.
        Assert.Equal("sk-key-1", settings.ConnectionFor(TeacherService.Compatible, "https://api.example.com/v1", "m1").Key);

        File.WriteAllBytes(Path.Combine(_dir.FullName, "teacher.key"), [1, 2, 3, 4]);
        Assert.False(settings.HasKeyFor(TeacherService.Compatible, "https://api.example.com/v1"));
    }

    [Fact]
    public Task MainViewModelCancelPendingWorkCancelsBusyAndWaitingAndCleansImportTempDirs() => RunStaAsync(async () =>
    {
        var dbPath = Path.Combine(_dir.FullName, "study.db");
        using var book = Notebook.Open(dbPath, TimeProvider.System);
        var p1 = book.Write(book.NewPage(PieceKind.Essay).Id, "First", "Topic 1", "Body one.");
        book.HandIn(p1.Id);
        var p2 = book.Write(book.NewPage(PieceKind.Essay).Id, "Second", "Topic 2", "Body two.");
        book.HandIn(p2.Id);

        var tcs1 = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var tcs2 = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var call = 0;
        var settings = new TeacherSettings(_dir.FullName)
        {
            TeacherOverride = _ => new BlockingTeacher(() => Interlocked.Increment(ref call) == 1 ? tcs1 : tcs2),
        };
        var model = new MainViewModel(book, dbPath, settings);

        // Start AskTeacher on p2 (current page), then switch to p1 so p2 moves into _waiting, and start AskTeacher on p1.
        var task2 = model.Page!.AskTeacherCommand.ExecuteAsync(null);
        await Task.Delay(20);
        model.Selected = model.Contents.Single(l => l.Id == p1.Id);
        var task1 = model.Page!.AskTeacherCommand.ExecuteAsync(null);
        await Task.Delay(20);

        Assert.True(model.HasPendingWork);
        Assert.NotEmpty(model.PendingWorkLines());

        model.CancelPendingWork();
        await Task.WhenAll(task1, task2);

        Assert.False(model.HasPendingWork);

        // Temp directory cleanup only deletes newly created inkwell-import-* folders.
        var tempRoot = Path.Combine(_dir.FullName, "temp");
        Directory.CreateDirectory(tempRoot);
        var existing = Directory.CreateDirectory(Path.Combine(tempRoot, "inkwell-import-old")).FullName;
        var snapshot = MainViewModel.SnapshotImportTempDirs(tempRoot);
        var created = Directory.CreateDirectory(Path.Combine(tempRoot, "inkwell-import-new")).FullName;

        MainViewModel.CleanupImportTempDirs(tempRoot, snapshot);

        Assert.True(Directory.Exists(existing));
        Assert.False(Directory.Exists(created));
    });

    [Fact]
    public Task ImportOldAsyncLocksTabAndNewPageCommandsWhileRunning() => RunStaAsync(async () =>
    {
        var dbPath = Path.Combine(_dir.FullName, "study-import.db");
        using var book = Notebook.Open(dbPath, TimeProvider.System);
        var model = new MainViewModel(book, dbPath, new TeacherSettings(_dir.FullName));

        // Create a valid v8 old backup with enough rows or a locked/valid file to observe IsImportingOld state transitions.
        var oldDbPath = Path.Combine(_dir.FullName, "old.db");
        using (var oldConn = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={oldDbPath};Pooling=false"))
        {
            oldConn.Open();
            using var cmd = oldConn.CreateCommand();
            cmd.CommandText = """
                PRAGMA user_version = 8;
                CREATE TABLE coach_practices (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    material TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    submittedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    sourcePracticeId TEXT NOT NULL
                );
                INSERT INTO coach_practices VALUES ('id-1', 'WRITING', 'T', 'Task', 'Body', 0, 1700000000000, '');
                """;
            cmd.ExecuteNonQuery();
        }

        var canShowWhileImporting = new List<bool>();
        var canNewWhileImporting = new List<bool>();
        model.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainViewModel.IsImportingOld))
            {
                canShowWhileImporting.Add(model.ShowTabCommand.CanExecute(Book.Words));
                canNewWhileImporting.Add(model.NewPageCommand.CanExecute(null));
            }
        };

        await model.ImportOldAsync(oldDbPath);

        Assert.Equal([false, true], canShowWhileImporting);
        Assert.Equal([false, true], canNewWhileImporting);
        Assert.True(model.ShowTabCommand.CanExecute(Book.Words));
        Assert.True(model.NewPageCommand.CanExecute(null));
        Assert.Contains("搬好了", model.ImportNote);
    });

    private static Task RunStaAsync(Func<Task> action)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.InvokeAsync(async () =>
            {
                try
                {
                    await action();
                    completion.SetResult();
                }
                catch (Exception ex)
                {
                    completion.SetException(ex);
                }
                finally
                {
                    dispatcher.BeginInvokeShutdown(DispatcherPriority.Normal);
                }
            });
            Dispatcher.Run();
        })
        { IsBackground = true };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    private sealed class BlockingTeacher(Func<TaskCompletionSource<string>> next) : ITeacher
    {
        public async Task<string> AskAsync(string instruction, string material, bool jsonReply, CancellationToken cancel)
        {
            var tcs = next();
            using var reg = cancel.Register(() => tcs.TrySetCanceled(cancel));
            return await tcs.Task;
        }
    }
}
