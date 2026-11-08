namespace Zhupi.Core.Teacher;

/// <summary>
/// How long the teacher may think before marking, like the effort setting in coding assistants.
/// <see cref="Default"/> sends nothing, so each service keeps its own default; the other levels map to the
/// <c>low</c> / <c>medium</c> / <c>high</c> / <c>max</c> effort values that OpenAI's Responses API
/// (<c>reasoning.effort</c>) and OpenAI-style chat completions such as DeepSeek's (<c>reasoning_effort</c>) take.
/// Which values a model accepts varies, so an unsupported level comes back as an error and is never retried.
/// </summary>
public enum Depth
{
    Default,
    Low,
    Medium,
    High,
    Max,
}

public static class DepthNames
{
    /// <summary>The effort value sent to the service, or null to leave the field out.</summary>
    public static string? Effort(this Depth depth) => depth switch
    {
        Depth.Low => "low",
        Depth.Medium => "medium",
        Depth.High => "high",
        Depth.Max => "max",
        _ => null,
    };

    public static string Label(this Depth depth) => depth switch
    {
        Depth.Low => "浅",
        Depth.Medium => "中",
        Depth.High => "深",
        Depth.Max => "最深",
        _ => "默认",
    };
}
