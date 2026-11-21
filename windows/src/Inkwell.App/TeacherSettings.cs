using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Inkwell.Core.ChatGpt;
using Inkwell.Core.Teacher;

namespace Inkwell.App;

/// <summary>
/// Which teacher to ask. Address and model choices sit in plain <c>teacher.json</c>; an API key is encrypted
/// with Windows DPAPI into <c>teacher.key</c>, and a ChatGPT sign-in lives in <see cref="ChatGptVault"/>.
/// </summary>
public sealed class TeacherSettings(string directory)
{
    // Keeps the program's former name on purpose: changing it would make every saved secret unreadable.
    private static readonly byte[] Entropy = "Zhupi teacher key v1"u8.ToArray();

    private const string BoundKeyPrefix = "\nInkwell teacher key v2\n";
    private sealed record BoundKey(TeacherService Service, string Address, string Key);

    // Depth is optional so settings files written before it existed still load.
    private sealed record Saved(TeacherService Service, string Address, string Model, string? ChatGptModel, Depth Depth = Depth.Default);

    private string SettingsFile => Path.Combine(directory, "teacher.json");

    private string KeyFile => Path.Combine(directory, "teacher.key");

    public ChatGptVault Vault { get; } = new(directory);

    public TeacherService Service { get; private set; } = TeacherService.DeepSeek;

    public string Address { get; private set; } = TeacherConnection.DeepSeekAddress;

    public string Model { get; private set; } = "";

    public string ChatGptModel { get; private set; } = "";

    /// <summary>One depth for every service: it describes how carefully to mark, not a service setting.</summary>
    public Depth Depth { get; private set; } = Depth.Default;

    public bool HasKey => File.Exists(KeyFile);

    internal Func<HttpClient, ITeacher>? TeacherOverride { get; set; }

    public string Label => Service switch
    {
        TeacherService.ChatGpt => $"ChatGPT · {ChatGptModel}",
        TeacherService.DeepSeek => $"DeepSeek · {Model}",
        _ => $"{(Uri.TryCreate(Address, UriKind.Absolute, out var uri) ? uri.Host : "兼容接口")} · {Model}",
    } + (Depth == Depth.Default ? "" : $" · 深度{Depth.Label()}");

    public static TeacherSettings Load(string directory)
    {
        var settings = new TeacherSettings(directory);
        if (!File.Exists(settings.SettingsFile)) return settings;
        try
        {
            if (JsonSerializer.Deserialize<Saved>(File.ReadAllText(settings.SettingsFile)) is { } saved)
            {
                settings.Service = Enum.IsDefined(saved.Service) ? saved.Service : TeacherService.DeepSeek;
                settings.Address = settings.Service == TeacherService.DeepSeek ? TeacherConnection.DeepSeekAddress : saved.Address ?? "";
                settings.Model = saved.Model ?? "";
                settings.ChatGptModel = saved.ChatGptModel ?? "";
                settings.Depth = Enum.IsDefined(saved.Depth) ? saved.Depth : Depth.Default;
            }
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            // A damaged or unreadable settings file only costs re-entering the choice; the notebook is untouched.
        }
        return settings;
    }

    public bool HasKeyFor(TeacherService service, string address)
    {
        if (service == TeacherService.ChatGpt || !File.Exists(KeyFile)) return false;
        try
        {
            var checkedAddress = ApiAddress(service, address);
            var payload = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(KeyFile), Entropy, DataProtectionScope.CurrentUser));
            if (!payload.StartsWith(BoundKeyPrefix, StringComparison.Ordinal)) return false;
            var bound = JsonSerializer.Deserialize<BoundKey>(payload[BoundKeyPrefix.Length..]);
            return bound is not null &&
                   !string.IsNullOrWhiteSpace(bound.Key) &&
                   bound.Service == service &&
                   string.Equals(bound.Address, checkedAddress, StringComparison.Ordinal);
        }
        catch (Exception e) when (e is TeacherException or CryptographicException or JsonException or IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>Saves an API-key teacher; an empty <paramref name="key"/> keeps the stored one unless the address changed.</summary>
    public void Save(TeacherService service, string address, string model, string key, Depth depth)
    {
        if (service == TeacherService.ChatGpt)
        {
            SaveChatGpt(model, depth);
            return;
        }
        var checkedAddress = ApiAddress(service, address);
        var trimmedModel = model.Trim();
        var saved = new Saved(service, checkedAddress, trimmedModel, ChatGptModel, depth);
        if (string.IsNullOrWhiteSpace(key))
        {
            _ = BoundConnection(service, checkedAddress, trimmedModel);
            SaveSettings(saved);
        }
        else
        {
            var connection = new TeacherConnection(checkedAddress, key, trimmedModel);
            var payload = BoundKeyPrefix + JsonSerializer.Serialize(new BoundKey(service, checkedAddress, connection.Key));
            try
            {
                SaveWithKey(saved, ProtectedData.Protect(Encoding.UTF8.GetBytes(payload), Entropy, DataProtectionScope.CurrentUser));
            }
            catch (CryptographicException)
            {
                throw new TeacherException("Windows 没能加密 API Key，没有保存设置。");
            }
        }
    }

    public void SaveChatGpt(string model, Depth? depth = null) =>
        SaveSettings(new Saved(TeacherService.ChatGpt, Address, Model, model.Trim(), depth ?? Depth));

    public void ForgetKey()
    {
        if (File.Exists(KeyFile)) File.Delete(KeyFile);
    }

    /// <summary>The teacher to ask now; throws with a readable reason when something is missing.</summary>
    public ITeacher CreateTeacher(HttpClient http) => TeacherOverride is { } create
        ? create(http)
        : Service == TeacherService.ChatGpt
            ? new ChatGptTeacher(http, new ChatGptAuth(http, TimeProvider.System), Vault, TimeProvider.System, ChatGptModel, Depth,
                Vault.Load() ?? throw new TeacherException("还没登录 ChatGPT：到「设置 → 请老师」登录。"))
            : new ChatTeacher(http, Connection(), Depth);

    public TeacherConnection Connection()
    {
        if (Model.Length == 0) throw new TeacherException("还没选模型：到「设置」填一个模型名。");
        return ConnectionFor(Service, Address, Model);
    }

    public TeacherConnection ConnectionFor(TeacherService service, string address, string model) =>
        BoundConnection(service, ApiAddress(service, address), model);

    public string Recipient => Service == TeacherService.ChatGpt ? "ChatGPT（OpenAI）" : ApiAddress(Service, Address);

    private static string ApiAddress(TeacherService service, string address) => service switch
    {
        TeacherService.DeepSeek => TeacherConnection.DeepSeekAddress,
        TeacherService.Compatible => TeacherConnection.CheckedAddress(address).ToString(),
        _ => throw new TeacherException("请选择支持 API Key 的服务。"),
    };

    private TeacherConnection BoundConnection(TeacherService service, string address, string model)
    {
        BoundKey? bound;
        try
        {
            var payload = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(KeyFile), Entropy, DataProtectionScope.CurrentUser));
            if (!payload.StartsWith(BoundKeyPrefix, StringComparison.Ordinal))
            {
                throw new TeacherException("旧版 API Key 没有绑定服务地址。请确认当前服务后重新填写并保存 API Key。");
            }
            bound = JsonSerializer.Deserialize<BoundKey>(payload[BoundKeyPrefix.Length..]);
        }
        catch (FileNotFoundException)
        {
            throw new TeacherException("还没请老师：先到「设置」填好 API Key 和模型。");
        }
        catch (Exception e) when (e is CryptographicException or JsonException or IOException or UnauthorizedAccessException)
        {
            throw new TeacherException("保存的 API Key 读不了，请到「设置」重新填写。");
        }

        if (bound is null || string.IsNullOrWhiteSpace(bound.Key) || bound.Service != service ||
            !string.Equals(bound.Address, address, StringComparison.Ordinal))
        {
            throw new TeacherException("保存的 API Key 与当前服务或地址不匹配，请重新填写 API Key。");
        }
        return new TeacherConnection(address, bound.Key, model);
    }

    private void SaveWithKey(Saved saved, byte[] protectedKey)
    {
        var previous = File.Exists(KeyFile) ? File.ReadAllBytes(KeyFile) : null;
        AtomicWrite(KeyFile, protectedKey);
        try
        {
            SaveSettings(saved);
        }
        catch
        {
            try
            {
                if (previous is null)
                {
                    if (File.Exists(KeyFile)) File.Delete(KeyFile);
                }
                else
                {
                    AtomicWrite(KeyFile, previous);
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Preserve the primary SaveSettings failure if rollback cannot touch the key file.
            }
            throw;
        }
    }

    private void SaveSettings(Saved saved)
    {
        AtomicWrite(SettingsFile, JsonSerializer.SerializeToUtf8Bytes(saved));
        (Service, Address, Model, ChatGptModel, Depth) =
            (saved.Service, saved.Address, saved.Model, saved.ChatGptModel ?? "", saved.Depth);
    }

    private void AtomicWrite(string file, byte[] bytes)
    {
        Directory.CreateDirectory(directory);
        var temp = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
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
