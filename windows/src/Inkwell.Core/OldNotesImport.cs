using Microsoft.Data.Sqlite;

namespace Inkwell.Core;

public sealed record ImportSummary(int Essays, int Translations, int HandedIn, int SkippedOther);

/// <summary>
/// One-time move of essay and translation texts from the old phone app's database file (format version 8)
/// into an empty notebook. Only the writing itself travels: old reviews and word progress stay in the backup.
/// </summary>
public static class OldNotesImport
{
    private const int KnownFormat = 8;
    private const long MaxBackupBytes = 128L * 1024 * 1024;
    private const int MaxRows = 10_000;
    private const int MaxTextBytes = 16 * 1024 * 1024;

    public static ImportSummary Run(string oldDatabasePath, Notebook notebook) => Run(oldDatabasePath, notebook, CancellationToken.None);

    public static ImportSummary Run(string oldDatabasePath, Notebook notebook, CancellationToken cancel)
    {
        CheckTarget(oldDatabasePath, notebook);
        return Write(ReadBackup(oldDatabasePath, cancel), notebook, cancel);
    }

    public static async Task<ImportSummary> RunAsync(string oldDatabasePath, Notebook notebook, CancellationToken cancel)
    {
        CheckTarget(oldDatabasePath, notebook);
        var rows = await Task.Run(() => ReadBackup(oldDatabasePath, cancel), cancel);
        return Write(rows, notebook, cancel);
    }

    private static void CheckTarget(string oldDatabasePath, Notebook notebook)
    {
        if (!File.Exists(oldDatabasePath)) throw new NotebookException("找不到旧数据文件。");
        if (!notebook.IsEmpty) throw NotEmpty();
    }

    private static NotebookException NotEmpty() => new("只能搬进空本子，现在的本子里已经有内容，什么都没改。");

    private static List<OldRow> ReadBackup(string oldDatabasePath, CancellationToken cancel)
    {
        // Opening a WAL database touches its -shm file, so read a private copy and leave the backup untouched.
        var work = Directory.CreateTempSubdirectory("inkwell-import-");
        try
        {
            var copy = Path.Combine(work.FullName, "old.db");
            var remaining = MaxBackupBytes;
            var buffer = new byte[64 * 1024];
            foreach (var suffix in new[] { "", "-wal" })
            {
                cancel.ThrowIfCancellationRequested();
                var source = oldDatabasePath + suffix;
                if (suffix.Length != 0 && !File.Exists(source)) continue;
                try
                {
                    using var input = File.OpenRead(source);
                    if (input.Length > remaining) throw BackupTooLarge();
                    using var output = File.Create(copy + suffix);
                    while (true)
                    {
                        cancel.ThrowIfCancellationRequested();
                        var count = input.Read(buffer, 0, (int)Math.Min(buffer.Length, remaining + 1));
                        if (count == 0) break;
                        if (count > remaining) throw BackupTooLarge();
                        output.Write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    throw new NotebookException("旧数据文件现在读不了，可能正在被其他程序使用，请先关闭后再试。");
                }
            }
            return ReadOld(copy, cancel);
        }
        finally
        {
            try
            {
                if (work.Exists) work.Delete(recursive: true);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    private static NotebookException BackupTooLarge() => new("旧备份及其附属文件超过 128 MiB，没有搬。");

    private sealed record OldRow(string Id, string Kind, string Title, string Task, string Text, long SubmittedAt, long UpdatedAt, string Source);

    private static List<OldRow> ReadOld(string path, CancellationToken cancel)
    {
        cancel.ThrowIfCancellationRequested();
        try
        {
            using var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
            db.Open();
            using (var check = db.CreateCommand())
            {
                check.CommandText = "PRAGMA trusted_schema = OFF; PRAGMA ignore_check_constraints = ON;";
                check.ExecuteNonQuery();
                check.CommandText = "PRAGMA user_version;";
                if (Convert.ToInt32(check.ExecuteScalar()) != KnownFormat) throw new NotebookException("旧数据文件的版本不认识，没有搬。");
                check.CommandText = "SELECT type FROM pragma_table_list WHERE schema = 'main' AND name = 'coach_practices';";
                if (check.ExecuteScalar() is not "table") throw new NotebookException("旧数据文件没有预期的练习表，没有搬。");
                check.CommandText = """
                    SELECT COUNT(*) FROM pragma_table_xinfo('coach_practices', 'main')
                    WHERE hidden = 0 AND name IN ('id', 'kind', 'title', 'material', 'answer', 'submittedAt', 'updatedAt', 'sourcePracticeId');
                    """;
                if (Convert.ToInt32(check.ExecuteScalar()) != 8) throw new NotebookException("旧数据文件的练习字段不完整，没有搬。");
                cancel.ThrowIfCancellationRequested();
                check.CommandText = "PRAGMA quick_check(1);";
                using (cancel.Register(() => check.Cancel()))
                {
                    if (Convert.ToString(check.ExecuteScalar()) != "ok") throw new NotebookException("旧数据文件已损坏，没有搬。");
                }
            }

            cancel.ThrowIfCancellationRequested();
            using var command = db.CreateCommand();
            command.CommandText = """
                SELECT id, kind, title, material, answer, submittedAt, updatedAt, sourcePracticeId,
                       length(CAST(id AS BLOB)) + length(CAST(kind AS BLOB)) + length(CAST(title AS BLOB))
                       + length(CAST(material AS BLOB)) + length(CAST(answer AS BLOB)) + length(CAST(sourcePracticeId AS BLOB)),
                       typeof(id), typeof(kind), typeof(title), typeof(material), typeof(answer),
                       typeof(submittedAt), typeof(updatedAt), typeof(sourcePracticeId)
                FROM main.coach_practices LIMIT $max;
                """;
            command.Parameters.AddWithValue("$max", MaxRows + 1);
            using (cancel.Register(() => command.Cancel()))
            {
                using var reader = command.ExecuteReader();
                var rows = new List<OldRow>();
                var seenIds = new HashSet<string>(StringComparer.Ordinal);
                var textBytes = 0L;
                var minTime = DateTimeOffset.MinValue.ToUnixTimeMilliseconds();
                var maxTime = DateTimeOffset.MaxValue.ToUnixTimeMilliseconds();
                while (reader.Read())
                {
                    cancel.ThrowIfCancellationRequested();
                    if (rows.Count == MaxRows) throw new NotebookException("旧备份的练习条目过多，没有搬。");
                    if (reader.GetString(9) != "text" || reader.GetString(10) != "text" || reader.GetString(11) != "text"
                        || reader.GetString(12) != "text" || reader.GetString(13) != "text" || reader.GetString(16) != "text")
                    {
                        throw new NotebookException("旧备份包含空的或格式不对的必填字段，没有搬。");
                    }
                    if (reader.GetString(14) != "integer" || reader.GetString(15) != "integer")
                    {
                        throw new NotebookException("旧备份包含无效日期，没有搬。");
                    }
                    if (reader.IsDBNull(8)) throw new NotebookException("旧备份包含空的必填字段，没有搬。");
                    textBytes += reader.GetInt64(8);
                    if (textBytes > MaxTextBytes) throw new NotebookException("旧备份的正文总量过大，没有搬。");
                    var id = reader.GetString(0);
                    if (string.IsNullOrWhiteSpace(id)) throw new NotebookException("旧备份包含空的练习编号，没有搬。");
                    if (!seenIds.Add(id)) throw new NotebookException("旧备份里存在重复的练习编号，没有搬。");
                    var row = new OldRow(id, reader.GetString(1), reader.GetString(2), reader.GetString(3),
                        reader.GetString(4), reader.GetInt64(5), reader.GetInt64(6), reader.GetString(7));
                    if (row.UpdatedAt < minTime || row.UpdatedAt > maxTime || row.SubmittedAt < 0 || row.SubmittedAt > maxTime)
                    {
                        throw new NotebookException("旧备份包含无效日期，没有搬。");
                    }
                    rows.Add(row);
                }
                return rows;
            }
        }
        catch (SqliteException) when (cancel.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancel);
        }
        catch (SqliteException)
        {
            throw new NotebookException("旧数据文件已损坏或不是有效的 SQLite 文件，没有搬。");
        }
    }

    private static ImportSummary Write(List<OldRow> rows, Notebook notebook, CancellationToken cancel)
    {
        cancel.ThrowIfCancellationRequested();
        var kept = rows.Where(r => r.Kind is "WRITING" or "TRANSLATION")
            .OrderBy(r => r.UpdatedAt).ThenBy(r => r.Id, StringComparer.Ordinal).ToList();
        var keptKinds = kept.ToDictionary(r => r.Id, r => r.Kind, StringComparer.Ordinal);
        var numbers = new Dictionary<PieceKind, int> { [PieceKind.Essay] = 0, [PieceKind.Translation] = 0 };
        using var tx = notebook.BeginTransaction();
        if (!notebook.IsEmptyIn(tx)) throw NotEmpty();
        // A revision may be older than its source in update order; check references only at commit.
        using (var defer = tx.Connection!.CreateCommand())
        {
            defer.Transaction = tx;
            defer.CommandText = "PRAGMA defer_foreign_keys = ON;";
            defer.ExecuteNonQuery();
        }
        foreach (var row in kept)
        {
            cancel.ThrowIfCancellationRequested();
            var kind = row.Kind == "WRITING" ? PieceKind.Essay : PieceKind.Translation;
            var updated = DateTimeOffset.FromUnixTimeMilliseconds(row.UpdatedAt);
            try
            {
                notebook.InsertRow(new Piece(
                    Id: row.Id,
                    Kind: kind,
                    Number: ++numbers[kind],
                    Title: row.Title,
                    Task: row.Task,
                    Text: row.Text,
                    Revises: row.Source != row.Id && keptKinds.TryGetValue(row.Source, out var sourceKind) && sourceKind == row.Kind
                        ? row.Source
                        : null,
                    CreatedAt: updated,
                    UpdatedAt: updated,
                    HandedInAt: row.SubmittedAt > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(row.SubmittedAt) : null,
                    Imported: true), tx);
            }
            catch (SqliteException) when (cancel.IsCancellationRequested)
            {
                throw new OperationCanceledException(cancel);
            }
            catch (SqliteException e) when (e.SqliteErrorCode == 19)
            {
                throw new NotebookException("旧备份里存在重复或冲突的练习记录，没有搬。");
            }
        }
        cancel.ThrowIfCancellationRequested();
        tx.Commit();
        return new ImportSummary(numbers[PieceKind.Essay], numbers[PieceKind.Translation],
            kept.Count(r => r.SubmittedAt > 0), rows.Count - kept.Count);
    }
}
