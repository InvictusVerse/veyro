using System.Diagnostics;

namespace Veyro.Core.Diagnostics;

/// <summary>Debug-only diagnostics. Never pass keys, tokens, PINs, or file contents here.</summary>
public static class VeyroTrace
{
    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message, Exception? error = null)
        => Write("ERROR", error is null ? message : $"{message}: {error.GetType().Name}: {error.Message}");

    private static void Write(string level, string message)
    {
        string line = $"[Veyro][{level}][{DateTimeOffset.Now:O}] {message}";
        Debug.WriteLine(line);
        Trace.WriteLine(line);
        Console.WriteLine(line);
    }
}