namespace Zhupi.Core.Teacher;

/// <summary>Anything that can answer as the teacher: an API-key service or a signed-in ChatGPT plan.</summary>
public interface ITeacher
{
    /// <summary>
    /// Sends one instruction and the student's material and returns the full reply. With
    /// <paramref name="jsonReply"/> the service is asked, where it supports it, to return a single JSON object.
    /// </summary>
    Task<string> AskAsync(string instruction, string material, bool jsonReply, CancellationToken cancel);
}
