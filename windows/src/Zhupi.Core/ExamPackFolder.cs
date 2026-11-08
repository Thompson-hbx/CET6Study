namespace Zhupi.Core;

/// <summary>What importing a folder of packs changed, and which files could not be read at all.</summary>
public sealed record FolderImport(int Added, int Updated, int Unchanged, IReadOnlyList<PackSkip> Skipped, IReadOnlyList<string> Unreadable)
{
    public bool ChangedAnything => Added > 0 || Updated > 0 || Skipped.Count > 0 || Unreadable.Count > 0;
}

/// <summary>
/// Content packs kept next to the program for personal use. Importing is the same as choosing the files by
/// hand, and safe to repeat: papers already in the notebook at the same revision are left as they are.
/// </summary>
public static class ExamPackFolder
{
    public static FolderImport ImportAll(Notebook book, string folder)
    {
        int added = 0, updated = 0, unchanged = 0;
        var skipped = new List<PackSkip>();
        var unreadable = new List<string>();
        if (!Directory.Exists(folder)) return new FolderImport(0, 0, 0, skipped, unreadable);
        foreach (var file in Directory.EnumerateFiles(folder, "*.json").Order(StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                var result = book.ImportPapers(ExamPackFile.Read(file));
                (added, updated, unchanged) = (added + result.Added, updated + result.Updated, unchanged + result.Unchanged);
                skipped.AddRange(result.Skipped);
            }
            catch (Exception e) when (e is NotebookException or IOException or UnauthorizedAccessException)
            {
                // One bad file must not stop the others or the app from opening.
                unreadable.Add(Path.GetFileName(file));
            }
        }
        return new FolderImport(added, updated, unchanged, skipped, unreadable);
    }
}
