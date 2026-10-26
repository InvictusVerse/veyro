using System.Text;
using System.Text.RegularExpressions;

namespace Veyro.Core.Messaging;

/// <summary>
/// Recipient and body rules shared by SMS sending and dialing (04-PROTOCOL.md 6.3). The PC
/// validates exactly like the phone so an invalid request never leaves the PC; the phone still
/// re-validates because the wire is the trust boundary.
/// </summary>
public static partial class PhoneAddress
{
    public const int MaxAddresses = 10;
    public const int MaxBodyLength = 2000;
    /// <summary>Two numbers whose digits are suffixes of each other match from this many digits.</summary>
    private const int MinSuffixDigits = 7;

    [GeneratedRegex(@"^[+]?[0-9*#]{3,32}\z", RegexOptions.CultureInvariant)]
    private static partial Regex ValidAddress();

    /// <summary>
    /// Strips whitespace, dashes, dots and brackets, then checks <c>^[+]?[0-9*#]{3,32}$</c>.
    /// Returns the normalized form, or null when invalid.
    /// </summary>
    public static string? Normalize(string? raw)
    {
        if (raw is null) return null;
        var builder = new StringBuilder(raw.Length);
        foreach (char c in raw)
        {
            if (char.IsWhiteSpace(c) || c is '-' or '.' or '(' or ')' or '[' or ']') continue;
            builder.Append(c);
        }
        string value = builder.ToString();
        return ValidAddress().IsMatch(value) ? value : null;
    }

    /// <summary>1..10 addresses, each valid after <see cref="Normalize"/>.</summary>
    public static bool TryNormalizeAll(IEnumerable<string?>? raw, out IReadOnlyList<string> normalized)
    {
        normalized = [];
        if (raw is null) return false;
        var list = new List<string>();
        foreach (string? item in raw)
        {
            if (Normalize(item) is not { } value) return false;
            list.Add(value);
            if (list.Count > MaxAddresses) return false;
        }
        if (list.Count == 0) return false;
        normalized = list;
        return true;
    }

    public static bool IsValidBody(string? body) => body is { Length: >= 1 and <= MaxBodyLength };

    /// <summary>
    /// Loose match for numbers the phone may reformat (for example adding a country code):
    /// equal digit strings, or one a suffix of the other with at least 7 digits.
    /// </summary>
    public static bool SameNumber(string? a, string? b)
    {
        if (a is null || b is null) return false;
        string x = Digits(a), y = Digits(b);
        if (x.Length == 0 || y.Length == 0) return string.Equals(a.Trim(), b.Trim(), StringComparison.OrdinalIgnoreCase);
        if (x == y) return true;
        if (Math.Min(x.Length, y.Length) < MinSuffixDigits) return false;
        return x.EndsWith(y, StringComparison.Ordinal) || y.EndsWith(x, StringComparison.Ordinal);
    }

    private static string Digits(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (char c in value)
        {
            if (c is >= '0' and <= '9') builder.Append(c);
        }
        return builder.ToString();
    }
}

/// <summary>One outstanding phone reply with a timeout driven by an injectable clock.</summary>
internal sealed class ReplyWaiter<T>
{
    private readonly TaskCompletionSource<T> _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private ITimer? _timer;

    public Task<T> Task => _completion.Task;

    public void StartTimeout(TimeProvider time, TimeSpan timeout, Action onTimeout)
        => _timer = time.CreateTimer(_ => onTimeout(), null, timeout, Timeout.InfiniteTimeSpan);

    public void Complete(T value)
    {
        _timer?.Dispose();
        _completion.TrySetResult(value);
    }

    public void Fail(Exception error)
    {
        _timer?.Dispose();
        _completion.TrySetException(error);
    }
}
