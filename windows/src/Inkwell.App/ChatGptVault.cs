using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using Inkwell.Core.ChatGpt;

namespace Inkwell.App;

/// <summary>
/// Keeps the ChatGPT session encrypted with DPAPI for this Windows user. The issued client id and this
/// computer's host id are not credentials, so they sit in plain files and survive sign-out.
/// </summary>
public sealed class ChatGptVault(string directory) : ISessionVault
{
    // Keeps the program's former name on purpose: changing it would make every saved secret unreadable.
    private static readonly byte[] Entropy = "Zhupi ChatGPT session v1"u8.ToArray();
    private static readonly ConcurrentDictionary<string, Lock> Gates = new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _gate = Gates.GetOrAdd(NormalizeDirectory(directory), static _ => new Lock());

    private static string NormalizeDirectory(string path) =>
        Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));

    private string SessionFile => Path.Combine(directory, "chatgpt.session");

    private string HintFile => Path.Combine(directory, "chatgpt.hint");

    private string ClientFile => Path.Combine(directory, "chatgpt.client");

    private string HostFile => Path.Combine(directory, "chatgpt.host");

    /// <summary>Created once per computer and then never changed.</summary>
    public string HostId
    {
        get
        {
            lock (_gate)
            {
                try
                {
                    if (File.Exists(HostFile))
                    {
                        var existing = File.ReadAllText(HostFile).Trim();
                        if (existing.Length > 0) return existing;
                    }
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                }
                var id = ChatGptAuth.NewHostId();
                try
                {
                    AtomicWrite(HostFile, Encoding.UTF8.GetBytes(id));
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                }
                return id;
            }
        }
    }

    public string? RememberedClientId
    {
        get
        {
            lock (_gate)
            {
                try
                {
                    return File.Exists(ClientFile) ? File.ReadAllText(ClientFile).Trim() : null;
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    return null;
                }
            }
        }
        set
        {
            lock (_gate) Write(ClientFile, value, encrypt: false);
        }
    }

    public string? RememberedIdToken
    {
        get { lock (_gate) return Read(HintFile); }
        set { lock (_gate) Write(HintFile, value, encrypt: true); }
    }

    public ChatGptSession? Load()
    {
        lock (_gate) return Read(SessionFile) is { } json ? ChatGptSession.FromJson(json) : null;
    }

    public void Save(ChatGptSession session)
    {
        lock (_gate) Write(SessionFile, session.ToJson(), encrypt: true);
    }

    public void Clear()
    {
        lock (_gate)
        {
            if (File.Exists(SessionFile)) File.Delete(SessionFile);
        }
    }

    private static string? Read(string file)
    {
        if (!File.Exists(file)) return null;
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(file), Entropy, DataProtectionScope.CurrentUser));
        }
        catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException)
        {
            // Encrypted for another Windows account, damaged, or unreadable: behave as signed out instead of failing.
            return null;
        }
    }

    // Written to a unique temporary file first so a crash or concurrent reader never sees half a file.
    private void Write(string file, string? value, bool encrypt)
    {
        if (value is null)
        {
            if (File.Exists(file)) File.Delete(file);
            return;
        }
        var bytes = Encoding.UTF8.GetBytes(value);
        if (encrypt) bytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
        AtomicWrite(file, bytes);
    }

    private void AtomicWrite(string file, byte[] bytes)
    {
        Directory.CreateDirectory(directory);
        var temp = $"{file}.{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllBytes(temp, bytes);
            File.Move(temp, file, overwrite: true);
        }
        finally
        {
            try
            {
                if (File.Exists(temp)) File.Delete(temp);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }
}
