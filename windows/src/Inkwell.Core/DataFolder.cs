namespace Inkwell.Core;

/// <summary>
/// Where the notebook lives under the user's local app data. The program used to be called Zhupi and kept its
/// notebook in a "Zhupi" folder; the first start under the new name copies that folder across and leaves the
/// original untouched, so going back to an older build still finds everything.
/// </summary>
public static class DataFolder
{
    public const string Name = "Inkwell";
    public const string FormerName = "Zhupi";

    /// <summary>Copies the former folder into place when this is the first start under the new name.</summary>
    /// <returns>True when the former notebook was brought across.</returns>
    public static bool AdoptFormer(string localAppData)
    {
        var target = Path.Combine(localAppData, Name);
        var former = Path.Combine(localAppData, FormerName);
        if (Directory.Exists(target) || !File.Exists(Path.Combine(former, "study.db"))) return false;
        // Copy beside the target and rename at the end, so an interrupted copy never looks like a finished one.
        var staging = target + ".copying";
        if (Directory.Exists(staging)) Directory.Delete(staging, recursive: true);
        Copy(new DirectoryInfo(former), staging);
        Directory.Move(staging, target);
        return true;
    }

    private static void Copy(DirectoryInfo from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var file in from.EnumerateFiles()) file.CopyTo(Path.Combine(to, file.Name));
        foreach (var folder in from.EnumerateDirectories()) Copy(folder, Path.Combine(to, folder.Name));
    }
}
