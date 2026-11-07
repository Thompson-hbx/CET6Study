namespace Zhupi.Core.Teacher;

/// <summary>Anything that can mark a page: an API-key service or a signed-in ChatGPT plan.</summary>
public interface ITeacher
{
    /// <summary>Sends one instruction and the student's material; returns the reply text, expected to be one JSON object.</summary>
    Task<string> AskForJsonAsync(string instruction, string material, CancellationToken cancel);
}
