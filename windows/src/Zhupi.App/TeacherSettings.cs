using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Zhupi.Core.Teacher;

namespace Zhupi.App;

/// <summary>
/// Which teacher to ask. The address and model sit in plain <c>teacher.json</c>; the API key is encrypted
/// with Windows DPAPI for this user into <c>teacher.key</c>, and is never written anywhere else.
/// </summary>
public sealed class TeacherSettings(string directory)
{
    private static readonly byte[] Entropy = "Zhupi teacher key v1"u8.ToArray();

    private sealed record Saved(TeacherService Service, string Address, string Model);

    private string SettingsFile => Path.Combine(directory, "teacher.json");

    private string KeyFile => Path.Combine(directory, "teacher.key");

    public TeacherService Service { get; private set; } = TeacherService.DeepSeek;

    public string Address { get; private set; } = TeacherConnection.DeepSeekAddress;

    public string Model { get; private set; } = "";

    public bool HasKey => File.Exists(KeyFile);

    public string Label => $"{(Service == TeacherService.DeepSeek ? "DeepSeek" : new Uri(Address).Host)} · {Model}";

    public static TeacherSettings Load(string directory)
    {
        var settings = new TeacherSettings(directory);
        if (!File.Exists(settings.SettingsFile)) return settings;
        try
        {
            var saved = JsonSerializer.Deserialize<Saved>(File.ReadAllText(settings.SettingsFile));
            if (saved is not null)
            {
                settings.Service = saved.Service;
                settings.Address = saved.Address;
                settings.Model = saved.Model;
            }
        }
        catch (JsonException)
        {
            // A damaged settings file only costs the learner re-entering the address; the notebook is untouched.
        }
        return settings;
    }

    /// <summary>Saves the choice; an empty <paramref name="key"/> keeps the stored one unless the address changed.</summary>
    public void Save(TeacherService service, string address, string model, string key)
    {
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
        File.WriteAllText(SettingsFile, JsonSerializer.Serialize(new Saved(service, checkedAddress, model.Trim())));
        (Service, Address, Model) = (service, checkedAddress, model.Trim());
    }

    public void ForgetKey()
    {
        if (HasKey) File.Delete(KeyFile);
    }

    /// <summary>The connection to use now, or null with the reason when something is missing.</summary>
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
}
