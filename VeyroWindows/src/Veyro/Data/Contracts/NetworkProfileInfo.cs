namespace Veyro.Data.Contracts;

/// <summary>A connected network and whether Windows treats it as Public.</summary>
public sealed record NetworkProfileInfo(string InterfaceName, bool IsPublic);
