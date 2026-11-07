using System.IO;
using System.Security.Cryptography;
using System.Text;
using Zhupi.Core.ChatGpt;

namespace Zhupi.App;

/// <summary>
/// Keeps the ChatGPT session encrypted with DPAPI for this Windows user. The issued client id and this
/// computer's host id are not credentials, so they sit in plain files and survive sign-out.
/// </summary>
public sealed class ChatGptVault(string directory) : ISessionVault
{
    private static readonly byte[] Entropy = "Zhupi ChatGPT session v1"u8.ToArray();

    private string SessionFile => Path.Combine(directory, "chatgpt.session");

    private string HintFile => Path.Combine(directory, "chatgpt.hint");

    private string ClientFile => Path.Combine(directory, "chatgpt.client");

    private string HostFile => Path.Combine(directory, "chatgpt.host");

    /// <summary>Created once per computer and then never changed.</summary>
    public string HostId
    {
        get
        {
            if (File.Exists(HostFile)) return File.ReadAllText(HostFile).Trim();
            Directory.CreateDirectory(directory);
            var id = ChatGptAuth.NewHostId();
            File.WriteAllText(HostFile, id);
            return id;
        }
    }

    public string? RememberedClientId
    {
        get => File.Exists(ClientFile) ? File.ReadAllText(ClientFile).Trim() : null;
        set => Write(ClientFile, value, encrypt: false);
    }

    public string? RememberedIdToken
    {
        get => Read(HintFile);
        set => Write(HintFile, value, encrypt: true);
    }

    public ChatGptSession? Load() => Read(SessionFile) is { } json ? ChatGptSession.FromJson(json) : null;

    public void Save(ChatGptSession session) => Write(SessionFile, session.ToJson(), encrypt: true);

    public void Clear()
    {
        if (File.Exists(SessionFile)) File.Delete(SessionFile);
    }

    private static string? Read(string file)
    {
        if (!File.Exists(file)) return null;
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(file), Entropy, DataProtectionScope.CurrentUser));
        }
        catch (CryptographicException)
        {
            // Encrypted for another Windows account or damaged: behave as signed out instead of failing.
            return null;
        }
    }

    // Written to a temporary file first so a crash never leaves half a credential behind.
    private void Write(string file, string? value, bool encrypt)
    {
        if (value is null)
        {
            if (File.Exists(file)) File.Delete(file);
            return;
        }
        Directory.CreateDirectory(directory);
        var bytes = Encoding.UTF8.GetBytes(value);
        if (encrypt) bytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
        var temp = file + ".tmp";
        File.WriteAllBytes(temp, bytes);
        File.Move(temp, file, overwrite: true);
    }
}
