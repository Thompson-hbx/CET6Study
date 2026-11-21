using System.IO;
using System.Security.Cryptography;
using System.Text;
using Inkwell.Core.ChatGpt;

namespace Inkwell.App.Tests;

public sealed class ChatGptVaultTest : IDisposable
{
    private readonly DirectoryInfo _dir = Directory.CreateTempSubdirectory("inkwell-vault-");

    public void Dispose() => _dir.Delete(recursive: true);

    [Fact]
    public void RoundTripsSessionHostIdAndRememberedFields()
    {
        var vault = new ChatGptVault(_dir.FullName);
        var hostId = vault.HostId;
        Assert.StartsWith("urn:uuid:", hostId, StringComparison.Ordinal);
        Assert.Equal(hostId, new ChatGptVault(_dir.FullName).HostId);

        var session = new ChatGptSession(
            "oaiapp_1",
            "sub-1",
            "a@b.com",
            "id-tok",
            "acc-tok",
            "ref-tok",
            new DateTimeOffset(2026, 11, 20, 12, 0, 0, TimeSpan.Zero),
            ["openid", ChatGptSession.PlanScope])
        {
            GrantId = Guid.NewGuid(),
        };

        vault.Save(session);
        vault.RememberedClientId = "oaiapp_1";
        vault.RememberedIdToken = "id-tok";

        var reloaded = new ChatGptVault(_dir.FullName);
        Assert.Equal(session.GrantId, reloaded.Load()!.GrantId);
        Assert.Equal("oaiapp_1", reloaded.RememberedClientId);
        Assert.Equal("id-tok", reloaded.RememberedIdToken);

        reloaded.Clear();
        Assert.Null(reloaded.Load());
        Assert.Equal("oaiapp_1", reloaded.RememberedClientId);
    }

    [Fact]
    public async Task ConcurrentReadsAndWritesDoNotThrowOrLeaveTempFiles()
    {
        var vault = new ChatGptVault(_dir.FullName);
        var tasks = Enumerable.Range(0, 8).Select(i => Task.Run(() =>
        {
            for (var j = 0; j < 20; j++)
            {
                _ = vault.HostId;
                vault.RememberedClientId = $"client-{i}";
                _ = vault.RememberedClientId;
                vault.RememberedIdToken = $"id-{i}-{j}";
                _ = vault.RememberedIdToken;
                vault.Save(new ChatGptSession(
                    $"client-{i}",
                    "sub",
                    "a@b.com",
                    "id",
                    $"acc-{j}",
                    $"ref-{j}",
                    DateTimeOffset.UtcNow.AddHours(1),
                    [ChatGptSession.PlanScope])
                {
                    GrantId = Guid.NewGuid(),
                });
                _ = vault.Load();
            }
        }));

        await Task.WhenAll(tasks);

        Assert.NotNull(vault.Load());
        Assert.Empty(_dir.GetFiles("*.tmp"));
    }

    [Fact]
    public void DamagedOrLockedFilesDoNotCrashGettersOrLoad()
    {
        var vault = new ChatGptVault(_dir.FullName);
        File.WriteAllBytes(Path.Combine(_dir.FullName, "chatgpt.session"), [1, 2, 3, 4]);
        Assert.Null(vault.Load());

        using var lockedClient = new FileStream(
            Path.Combine(_dir.FullName, "chatgpt.client"), FileMode.Create, FileAccess.ReadWrite, FileShare.None);
        Assert.Null(vault.RememberedClientId);

        using var lockedHost = new FileStream(
            Path.Combine(_dir.FullName, "chatgpt.host"), FileMode.Create, FileAccess.ReadWrite, FileShare.None);
        Assert.StartsWith("urn:uuid:", vault.HostId, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("""{"client_id":"c","subject":"s","email":"","id_token":"i","access_token":"a","refresh_token":"r"}""")]
    public void MalformedDpapiSessionJsonLoadsAsNullWithoutThrowing(string json)
    {
        var entropy = "Zhupi ChatGPT session v1"u8.ToArray();
        var protectedBytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(json), entropy, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(Path.Combine(_dir.FullName, "chatgpt.session"), protectedBytes);

        var vault = new ChatGptVault(_dir.FullName);
        Assert.Null(vault.Load());
    }

    [Fact]
    public async Task CrossInstanceConcurrentReadsWritesAndDeletesDoNotThrowOrLeaveTempFiles()
    {
        string[] variants =
        [
            _dir.FullName,
            _dir.FullName + Path.DirectorySeparatorChar,
            Path.Combine(_dir.FullName, "."),
        ];

        var tasks = Enumerable.Range(0, 6).Select(i => Task.Run(() =>
        {
            for (var j = 0; j < 30; j++)
            {
                var vault = new ChatGptVault(variants[(i + j) % variants.Length]);
                _ = vault.HostId;
                vault.RememberedClientId = j % 5 == 0 ? null : $"client-{i}";
                _ = vault.RememberedClientId;
                vault.RememberedIdToken = j % 7 == 0 ? null : $"id-{i}-{j}";
                _ = vault.RememberedIdToken;
                vault.Save(new ChatGptSession(
                    $"client-{i}",
                    "sub",
                    "a@b.com",
                    "id",
                    $"acc-{j}",
                    $"ref-{j}",
                    DateTimeOffset.UtcNow.AddHours(1),
                    [ChatGptSession.PlanScope])
                {
                    GrantId = Guid.NewGuid(),
                });
                _ = vault.Load();
                if (j % 3 == 0) vault.Clear();
            }
        }));

        await Task.WhenAll(tasks);

        Assert.Empty(_dir.GetFiles("*.tmp"));
    }
}
