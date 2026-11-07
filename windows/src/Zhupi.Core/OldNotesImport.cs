using Microsoft.Data.Sqlite;

namespace Zhupi.Core;

public sealed record ImportSummary(int Essays, int Translations, int HandedIn, int SkippedOther);

/// <summary>
/// One-time move of essay and translation texts from the old phone app's database file (format version 8)
/// into an empty notebook. Only the writing itself travels: old reviews and word progress stay in the backup.
/// </summary>
public static class OldNotesImport
{
    private const int KnownFormat = 8;

    public static ImportSummary Run(string oldDatabasePath, Notebook notebook)
    {
        if (!File.Exists(oldDatabasePath)) throw new NotebookException("找不到旧数据文件。");
        if (!notebook.IsEmpty) throw new NotebookException("只能搬进空本子，现在的本子里已经有内容，什么都没改。");

        // Opening a WAL database touches its -shm file, so read a private copy and leave the backup untouched.
        var work = Directory.CreateTempSubdirectory("zhupi-import-");
        try
        {
            var copy = Path.Combine(work.FullName, "old.db");
            foreach (var suffix in new[] { "", "-wal", "-shm" })
            {
                if (File.Exists(oldDatabasePath + suffix)) File.Copy(oldDatabasePath + suffix, copy + suffix);
            }
            var rows = ReadOld(copy);
            return Write(rows, notebook);
        }
        finally
        {
            work.Delete(recursive: true);
        }
    }

    private sealed record OldRow(string Id, string Kind, string Title, string Task, string Text, long SubmittedAt, long UpdatedAt, string Source);

    private static List<OldRow> ReadOld(string path)
    {
        using var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        db.Open();
        using (var check = db.CreateCommand())
        {
            check.CommandText = "PRAGMA user_version;";
            if (Convert.ToInt32(check.ExecuteScalar()) != KnownFormat) throw new NotebookException("旧数据文件的版本不认识，没有搬。");
            check.CommandText = "PRAGMA integrity_check;";
            if (Convert.ToString(check.ExecuteScalar()) != "ok") throw new NotebookException("旧数据文件已损坏，没有搬。");
        }
        using var command = db.CreateCommand();
        command.CommandText =
            "SELECT id, kind, title, material, answer, submittedAt, updatedAt, sourcePracticeId FROM coach_practices ORDER BY updatedAt, id;";
        using var reader = command.ExecuteReader();
        var rows = new List<OldRow>();
        while (reader.Read())
        {
            rows.Add(new OldRow(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3),
                reader.GetString(4), reader.GetInt64(5), reader.GetInt64(6), reader.GetString(7)));
        }
        return rows;
    }

    private static ImportSummary Write(List<OldRow> rows, Notebook notebook)
    {
        var kept = rows.Where(r => r.Kind is "WRITING" or "TRANSLATION").ToList();
        var keptIds = kept.Select(r => r.Id).ToHashSet();
        var numbers = new Dictionary<PieceKind, int> { [PieceKind.Essay] = 0, [PieceKind.Translation] = 0 };
        using var tx = notebook.BeginTransaction();
        // A revision may be older than its source in update order; check references only at commit.
        using (var defer = tx.Connection!.CreateCommand())
        {
            defer.Transaction = tx;
            defer.CommandText = "PRAGMA defer_foreign_keys = ON;";
            defer.ExecuteNonQuery();
        }
        foreach (var row in kept)
        {
            var kind = row.Kind == "WRITING" ? PieceKind.Essay : PieceKind.Translation;
            var updated = DateTimeOffset.FromUnixTimeMilliseconds(row.UpdatedAt);
            notebook.InsertRow(new Piece(
                Id: row.Id,
                Kind: kind,
                Number: ++numbers[kind],
                Title: row.Title,
                Task: row.Task,
                Text: row.Text,
                Revises: keptIds.Contains(row.Source) && row.Source != row.Id ? row.Source : null,
                CreatedAt: updated,
                UpdatedAt: updated,
                HandedInAt: row.SubmittedAt > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(row.SubmittedAt) : null,
                Imported: true), tx);
        }
        tx.Commit();
        return new ImportSummary(numbers[PieceKind.Essay], numbers[PieceKind.Translation],
            kept.Count(r => r.SubmittedAt > 0), rows.Count - kept.Count);
    }
}
