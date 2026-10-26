using System.Diagnostics;

namespace Veyro.Core.Diagnostics;

/// <summary>
/// Diagnostics. Never pass keys, tokens, PINs, file names or file contents here.
///
/// Lines also go to a small rolling file so a hardware test can be diagnosed without a debugger:
/// <c>%LOCALAPPDATA%\Veyro\logs\veyro.log</c> (inside the package's LocalCache when packaged),
/// at most 1 MiB, previous file kept as <c>veyro.1.log</c>.
/// </summary>
public static class VeyroTrace
{
    private const long MaxFileBytes = 1024 * 1024;
    private static readonly object FileGate = new();
    private static string? _filePath = DefaultPath();

    /// <summary>Full path of the current log file, or null when file logging is unavailable.</summary>
    public static string? FilePath => _filePath;

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message, Exception? error = null)
        => Write("ERROR", error is null ? message : $"{message}: {error.GetType().Name}: {error.Message}");

    /// <summary>
    /// A crash: exception type, HRESULT and stack trace, but not the message (messages can name the
    /// user's files). Inner exceptions are walked the same way.
    /// </summary>
    public static void Crash(string where, Exception error)
    {
        var lines = new System.Text.StringBuilder($"{where}:");
        for (Exception? e = error; e is not null; e = e.InnerException)
        {
            lines.Append($"\n  {e.GetType().FullName} (0x{e.HResult:X8})\n{e.StackTrace}");
        }
        Write("CRASH", lines.ToString());
    }

    private static void Write(string level, string message)
    {
        string line = $"[Veyro][{level}][{DateTimeOffset.Now:O}] {message}";
        Debug.WriteLine(line);
        Trace.WriteLine(line);
        Console.WriteLine(line);
        AppendToFile(line);
    }

    private static void AppendToFile(string line)
    {
        string? path = _filePath;
        if (path is null) return;
        lock (FileGate)
        {
            try
            {
                var info = new FileInfo(path);
                if (info.Exists && info.Length > MaxFileBytes)
                {
                    File.Move(path, Path.ChangeExtension(path, ".1.log"), overwrite: true);
                }
                File.AppendAllText(path, line + Environment.NewLine);
            }
            catch (Exception)
            {
                // Logging must never break the app; give up on the file for this process.
                _filePath = null;
            }
        }
    }

    private static string? DefaultPath()
    {
        try
        {
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Veyro", "logs");
            Directory.CreateDirectory(dir);
            return Path.Combine(dir, "veyro.log");
        }
        catch (Exception)
        {
            return null;
        }
    }
}
