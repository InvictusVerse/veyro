namespace Veyro.Data.Models;

/// <summary>One line of the pairing screen's network check.</summary>
public sealed record NetworkCheckUiModel(
    string Title,
    string Detail,
    bool IsOk,
    string? ActionText = null,
    NetworkCheckAction Action = NetworkCheckAction.None)
{
    public bool IsProblem => !IsOk;
    public bool HasAction => ActionText is not null;
}
