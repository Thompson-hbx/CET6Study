using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Zhupi.Core.ChatGpt;
using Zhupi.Core.Teacher;

namespace Zhupi.App;

/// <summary>
/// Which teacher to ask. Address and model choices sit in plain <c>teacher.json</c>; an API key is encrypted
/// with Windows DPAPI into <c>teacher.key</c>, and a ChatGPT sign-in lives in <see cref="ChatGptVault"/>.
/// </summary>
public sealed class TeacherSettings(string directory)
{
    private static readonly byte[] Entropy = "Zhupi teacher key v1"u8.ToArray();

    private sealed record Saved(TeacherService Service, string Address, string Model, string? ChatGptModel);

    private string SettingsFile => Path.Combine(directory, "teacher.json");

    private string KeyFile => Path.Combine(directory, "teacher.key");

    public ChatGptVault Vault { get; } = new(directory);

    public TeacherService Service { get; private set; } = TeacherService.DeepSeek;

    public string Address { get; private set; } = TeacherConnection.DeepSeekAddress;

    public string Model { get; private set; } = "";

    public string ChatGptModel { get; private set; } = "";

    public bool HasKey => File.Exists(KeyFile);

    public string Label => Service switch
    {
        TeacherService.ChatGpt => $"ChatGPT · {ChatGptModel}",
        TeacherService.DeepSeek => $"DeepSeek · {Model}",
        _ => $"{new Uri(Address).Host} · {Model}",
    };

    public static TeacherSettings Load(string directory)
    {
        var settings = new TeacherSettings(directory);
        if (!File.Exists(settings.SettingsFile)) return settings;
        try
        {
            if (JsonSerializer.Deserialize<Saved>(File.ReadAllText(settings.SettingsFile)) is { } saved)
            {
                (settings.Service, settings.Address, settings.Model, settings.ChatGptModel) =
                    (saved.Service, saved.Address, saved.Model, saved.ChatGptModel ?? "");
            }
        }
        catch (JsonException)
        {
            // A damaged settings file only costs re-entering the choice; the notebook is untouched.
        }
        return settings;
    }

    /// <summary>Saves an API-key teacher; an empty <paramref name="key"/> keeps the stored one unless the address changed.</summary>
    public void Save(TeacherService service, string address, string model, string key)
    {
        if (service == TeacherService.ChatGpt)
        {
            SaveChatGpt(model);
            return;
        }
        var checkedAddress = TeacherConnection.CheckedAddress(service == TeacherService.DeepSeek ? TeacherConnection.DeepSeekAddress : address).ToString();
        var addressChanged = checkedAddress != Address;
        if (key.Trim().Length == 0 && (!HasKey || addressChanged))
        {
            throw new TeacherException(addressChanged && HasKey ? "换了地址，要重新填 API Key。" : "先填 API Key。");
        }
        if (key.Trim().Length > 0) _ = new TeacherConnection(checkedAddress, key, model);
        Directory.CreateDirectory(directory);
        if (key.Trim().Length > 0)
        {
            File.WriteAllBytes(KeyFile, ProtectedData.Protect(Encoding.UTF8.GetBytes(key.Trim()), Entropy, DataProtectionScope.CurrentUser));
        }
        (Service, Address, Model) = (service, checkedAddress, model.Trim());
        Persist();
    }

    public void SaveChatGpt(string model)
    {
        (Service, ChatGptModel) = (TeacherService.ChatGpt, model.Trim());
        Persist();
    }

    public void ForgetKey()
    {
        if (HasKey) File.Delete(KeyFile);
    }

    /// <summary>The teacher to ask now; throws with a readable reason when something is missing.</summary>
    public ITeacher CreateTeacher(HttpClient http) => Service == TeacherService.ChatGpt
        ? new ChatGptTeacher(http, new ChatGptAuth(http, TimeProvider.System), Vault, TimeProvider.System, ChatGptModel)
        : new ChatTeacher(http, Connection());

    public TeacherConnection Connection()
    {
        if (!HasKey) throw new TeacherException("还没请老师：先到「设置」填好 API Key 和模型。");
        if (Model.Length == 0) throw new TeacherException("还没选模型：到「设置」填一个模型名。");
        string key;
        try
        {
            key = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(KeyFile), Entropy, DataProtectionScope.CurrentUser));
        }
        catch (CryptographicException)
        {
            throw new TeacherException("保存的 API Key 解不开了（可能换了 Windows 账户），请到「设置」重新填写。");
        }
        return new TeacherConnection(Address, key, Model);
    }

    private void Persist()
    {
        Directory.CreateDirectory(directory);
        File.WriteAllText(SettingsFile, JsonSerializer.Serialize(new Saved(Service, Address, Model, ChatGptModel)));
    }
}
