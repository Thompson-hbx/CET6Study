namespace Zhupi.Core;

public enum PieceKind
{
    Essay,
    Translation,
}

/// <summary>
/// One page in the notebook: a single draft of an essay or a translation. Once handed in it never changes;
/// rewriting it starts a new page that points back with <see cref="Revises"/>.
/// </summary>
public sealed record Piece(
    string Id,
    PieceKind Kind,
    int Number,
    string Title,
    string Task,
    string Text,
    string? Revises,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    DateTimeOffset? HandedInAt,
    bool Imported)
{
    public bool IsHandedIn => HandedInAt is not null;
}

/// <summary>The rejected operation and why, in words the learner can act on.</summary>
public sealed class NotebookException(string message) : Exception(message);
