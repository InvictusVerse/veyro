using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Veyro.Core.Notifications;

/// <summary>
/// Finds a one-time code (OTP, verification, login or sign-in code) in a mirrored notification,
/// for the "Copy code" button on the toast and in the feed (ADR-063). Pure: no state, no I/O.
///
/// A code is 4 to 8 digits (also written "123 456" or "123-456", in any Unicode decimal digits,
/// returned as ASCII), or 6 to 8 upper-case letters and digits with at least one of each. It is
/// only taken near a keyword such as code, OTP, verification, PIN or password, in several
/// languages, and the nearest one wins. Phone numbers, dates, times, amounts, long numbers, masked
/// card numbers and digits inside links are skipped. With only a weak keyword ("code", "PIN"),
/// promotional or order wording ("promo", "order", "tracking") means there is no code.
///
/// The code is user data: callers never log it.
/// </summary>
public static partial class OneTimeCodeDetector
{
    public const int MinDigits = 4;
    public const int MaxDigits = 8;
    public const int MinAlphanumeric = 6;
    public const int MaxAlphanumeric = 8;

    /// <summary>A candidate further than this many characters from every keyword is not taken.</summary>
    public const int MaxKeywordDistance = 120;

    /// <summary>Codes arrive in short messages; only the start of a long title or text is read.</summary>
    public const int MaxTitleLength = 300;
    public const int MaxTextLength = 2000;

    private readonly record struct Span(int Start, int End);

    private readonly record struct Candidate(int Start, int End, string Value, bool Digits);

    private static readonly HashSet<char> BadBefore = ['+', '#', '*', '@', '/', '\\', '=', '&', '?', '_', '~', '\u00A9', '\u2116'];
    private static readonly HashSet<char> BadAfter = ['%', '\u2030', '\u00B0', '/', '@', '_', '=', '*', '\\', '&'];

    private static readonly HashSet<string> Units = new(StringComparer.Ordinal)
    {
        "am", "pm", "h", "hr", "hrs", "hour", "hours", "min", "mins", "minute", "minutes", "sec", "secs",
        "second", "seconds", "ms", "day", "days", "week", "weeks", "month", "months", "year", "years",
        "km", "kms", "m", "mi", "mile", "miles", "ft", "kg", "kgs", "g", "lb", "lbs", "kb", "mb", "gb",
        "tb", "mah", "mm", "cm", "hz", "khz", "mhz", "ghz", "pts", "points", "steps", "kcal", "cal", "uhr", "hs", "x",
    };

    private static readonly HashSet<string> CurrencyWords = new(StringComparer.Ordinal)
    {
        "rs", "inr", "usd", "eur", "gbp", "jpy", "cny", "rmb", "aud", "cad", "nzd", "chf", "sek", "nok", "dkk",
        "pln", "czk", "huf", "rub", "uah", "tl", "brl", "mxn", "ars", "clp", "cop", "pen", "zar", "ngn", "kes",
        "ghs", "egp", "aed", "sar", "qar", "kwd", "omr", "bhd", "ils", "pkr", "bdt", "lkr", "npr", "idr", "rp",
        "myr", "rm", "sgd", "hkd", "twd", "thb", "baht", "vnd", "php", "krw", "won", "yen", "yuan", "dollar",
        "dollars", "euro", "euros", "pound", "pounds", "rupee", "rupees", "rupiah", "taka", "naira", "rand",
        "peso", "pesos", "reais", "lira", "ruble", "rubles", "dirham", "riyal", "ringgit", "dong",
    };

    /// <summary>A number right after one of these words is a phone, card, account or order number.</summary>
    private static readonly HashSet<string> NumberLabels = new(StringComparer.Ordinal)
    {
        "call", "tel", "phone", "ph", "mob", "mobile", "cell", "fax", "dial", "helpline", "hotline", "card",
        "a/c", "acct", "account", "ending", "order", "ref", "invoice", "id", "no", "nr", "number", "flight",
        "room", "ext", "extension", "ticket", "case",
    };

    /// <summary>The code in <paramref name="title"/> and <paramref name="text"/>, or null.</summary>
    public static string? Find(string? title, string? text)
    {
        string head = Limit(title, MaxTitleLength);
        string body = Limit(text, MaxTextLength);
        if (head.Length == 0 && body.Length == 0) return null;
        string all = head + "\n" + body;

        var keywords = new List<Span>();
        bool strong = Collect(StrongKeywords(), all, keywords);
        Collect(WeakKeywords(), all, keywords);
        if (keywords.Count == 0) return null;
        if (!strong && NegativeContext().IsMatch(all)) return null;

        var candidates = new List<Candidate>();
        AddDigitCandidates(all, keywords, candidates);
        AddAlphanumericCandidates(all, candidates);

        string titleOnly = head.Trim();
        Candidate? best = null;
        int bestDistance = int.MaxValue;
        foreach (Candidate candidate in candidates)
        {
            // A title that is only a number is the sender (an SMS short code), not a code.
            if (candidate.End <= head.Length && all[candidate.Start..candidate.End] == titleOnly) continue;
            int distance = Distance(candidate, keywords);
            if (distance > MaxKeywordDistance) continue;
            if (best is null || distance < bestDistance || (distance == bestDistance && candidate.Digits && !best.Value.Digits))
            {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best?.Value;
    }

    /// <summary>The code in one text, or null.</summary>
    public static string? Find(string? text) => Find(null, text);

    /// <summary>
    /// True for a value this detector could return: 4 to 8 ASCII digits, or 6 to 8 upper-case
    /// letters and digits with at least one digit. Used to check a code that comes back from a toast.
    /// </summary>
    public static bool IsCode(string? value)
    {
        if (string.IsNullOrEmpty(value)) return false;
        if (value.All(char.IsAsciiDigit)) return value.Length is >= MinDigits and <= MaxDigits;
        return value.Length is >= MinAlphanumeric and <= MaxAlphanumeric
               && value.All(c => char.IsAsciiDigit(c) || char.IsAsciiLetterUpper(c))
               && value.Any(char.IsAsciiDigit);
    }

    private static string Limit(string? value, int max)
        => value is null ? string.Empty : value.Length <= max ? value : value[..max];

    private static bool Collect(Regex pattern, string text, List<Span> into)
    {
        bool any = false;
        foreach (Match match in pattern.Matches(text))
        {
            into.Add(new Span(match.Index, match.Index + match.Length));
            any = true;
        }
        return any;
    }

    private static int Distance(Candidate candidate, List<Span> keywords)
    {
        int best = int.MaxValue;
        foreach (Span keyword in keywords)
        {
            // The keyword is inside the token ("PIN1234" as one word): that token is not a code.
            if (keyword.Start < candidate.End && candidate.Start < keyword.End) return int.MaxValue;
            int distance = keyword.End <= candidate.Start ? candidate.Start - keyword.End : keyword.Start - candidate.End;
            if (distance < best) best = distance;
        }
        return best;
    }

    // ── Digits ────────────────────────────────────────────────────────────────────────

    private static void AddDigitCandidates(string s, List<Span> keywords, List<Candidate> output)
    {
        var groups = new List<Span>();
        var separators = new List<char>();
        int i = 0;
        while (i < s.Length)
        {
            if (!char.IsDigit(s[i]))
            {
                i++;
                continue;
            }
            groups.Clear();
            separators.Clear();
            int j = i;
            while (true)
            {
                int groupStart = j;
                while (j < s.Length && char.IsDigit(s[j])) j++;
                groups.Add(new Span(groupStart, j));
                if (j + 1 < s.Length && IsSeparator(s[j]) && char.IsDigit(s[j + 1]))
                {
                    separators.Add(s[j]);
                    j++;
                    continue;
                }
                break;
            }
            AddRun(s, i, j, groups, separators, keywords, output);
            i = j;
        }
    }

    /// <summary>One run of digits, possibly split by single separators ("123 456", "2026-10-30", "1,500.00").</summary>
    private static void AddRun(string s, int start, int end, List<Span> groups, List<char> separators, List<Span> keywords, List<Candidate> output)
    {
        // Times (12:30), dates (30/10, 30.10.2026), amounts and decimals (1,500 and 2.50).
        if (separators.Any(c => !IsSpace(c) && !IsHyphen(c))) return;
        // International numbers ("+44 20 ...") and the rest of "(555) 123 4567".
        if ((start > 0 && s[start - 1] == '+') || AfterBracketedDigits(s, start)) return;

        if (separators.Any(IsHyphen))
        {
            // Only "123-456" and "1234-5678"; "555-1234", "2026-10-30" and card numbers are not codes.
            if (groups.Count == 2 && separators.All(IsHyphen) && IsEvenPair(groups)) TryAdd(s, start, end, start, keywords, output);
            return;
        }
        if (groups.Count == 1 || (groups.Count == 2 && IsEvenPair(groups)))
        {
            TryAdd(s, start, end, start, keywords, output);
            return;
        }
        int digits = groups.Sum(g => g.End - g.Start);
        // Phone numbers written in groups ("0800 123 4567") and long numbers split by spaces.
        if (digits > MaxDigits || (groups.Count >= 3 && groups.All(g => g.End - g.Start <= 4))) return;
        // Unrelated numbers next to each other ("482913 2 min"): each on its own.
        foreach (Span group in groups) TryAdd(s, group.Start, group.End, start, keywords, output);
    }

    private static void TryAdd(string s, int start, int end, int runStart, List<Span> keywords, List<Candidate> output)
    {
        var value = new StringBuilder(end - start);
        for (int k = start; k < end; k++)
        {
            if (char.IsDigit(s[k])) value.Append((char)('0' + (int)char.GetNumericValue(s[k])));
        }
        if (value.Length is < MinDigits or > MaxDigits) return;

        char before = start > 0 ? s[start - 1] : '\0';
        char after = end < s.Length ? s[end] : '\0';
        // Glued to a word ("XX1234", "A1234", "1234abc"); straight after a keyword ("OTP123456") is fine.
        if (IsWordLetter(before) && !keywords.Any(k => k.End == start)) return;
        if (IsWordLetter(after)) return;
        // Links, ids, masks, percentages.
        if (BadBefore.Contains(before) || BadAfter.Contains(after)) return;
        if (before == '.' && start >= 2 && char.IsLetterOrDigit(s[start - 2])) return;
        if (after is '.' or ',' && end + 1 < s.Length && char.IsLetterOrDigit(s[end + 1])) return;
        if (IsCurrency(s, start, end) || Units.Contains(NextWord(s, end))) return;

        string normalized = value.ToString();
        if (LooksLikeDate(s, start, end, normalized) || IsLabelledNumber(s, runStart)) return;
        output.Add(new Candidate(start, end, normalized, Digits: true));
    }

    private static bool IsEvenPair(List<Span> groups)
    {
        int first = groups[0].End - groups[0].Start;
        int second = groups[1].End - groups[1].Start;
        return first == second && first is 3 or 4;
    }

    private static bool AfterBracketedDigits(string s, int start)
    {
        int k = start - 1;
        while (k >= 0 && IsSpace(s[k])) k--;
        return k >= 1 && s[k] == ')' && char.IsDigit(s[k - 1]);
    }

    private static bool IsCurrency(string s, int start, int end)
    {
        int k = start - 1;
        while (k >= 0 && IsSpace(s[k])) k--;
        if (k >= 0 && char.GetUnicodeCategory(s[k]) == UnicodeCategory.CurrencySymbol) return true;
        int j = end;
        while (j < s.Length && IsSpace(s[j])) j++;
        if (j < s.Length && char.GetUnicodeCategory(s[j]) == UnicodeCategory.CurrencySymbol) return true;
        return CurrencyWords.Contains(PrevWord(s, start, out _)) || CurrencyWords.Contains(NextWord(s, end));
    }

    private static bool LooksLikeDate(string s, int start, int end, string value)
    {
        if (value.Length == 4 && IsYear(value))
        {
            // "30 Oct 2026", "October 2026", "2026年".
            if (end < s.Length && s[end] == '\u5E74') return true;
            if (MonthName().IsMatch(Window(s, start - 16, start)) || MonthName().IsMatch(Window(s, end, end + 12))) return true;
        }
        // 20261030 (yyyymmdd).
        return value.Length == 8 && IsYear(value[..4])
               && int.Parse(value[4..6], CultureInfo.InvariantCulture) is >= 1 and <= 12
               && int.Parse(value[6..8], CultureInfo.InvariantCulture) is >= 1 and <= 31;
    }

    private static bool IsYear(string value) => value.StartsWith("19", StringComparison.Ordinal) || value.StartsWith("20", StringComparison.Ordinal);

    private static string Window(string s, int from, int to)
    {
        from = Math.Max(0, from);
        to = Math.Min(s.Length, to);
        return from < to ? s[from..to] : string.Empty;
    }

    /// <summary>"Call 5551234", "card ending 1234", "order 48213", "ending in 1234".</summary>
    private static bool IsLabelledNumber(string s, int runStart)
    {
        string previous = PrevWord(s, runStart, out int wordStart);
        if (NumberLabels.Contains(previous)) return true;
        return previous is "in" or "with" && PrevWord(s, wordStart, out _) == "ending";
    }

    /// <summary>The word before <paramref name="index"/>, skipping spaces and ".:#-", lower case.</summary>
    private static string PrevWord(string s, int index, out int wordStart)
    {
        int k = index - 1;
        while (k >= 0 && (IsSpace(s[k]) || s[k] is '.' or ':' or '#' or '-' or '\n')) k--;
        int end = k + 1;
        while (k >= 0 && (char.IsLetter(s[k]) || s[k] == '/')) k--;
        wordStart = k + 1;
        return s[wordStart..end].ToLowerInvariant();
    }

    /// <summary>The word after <paramref name="index"/>, skipping spaces, lower case.</summary>
    private static string NextWord(string s, int index)
    {
        int k = index;
        while (k < s.Length && IsSpace(s[k])) k++;
        int start = k;
        while (k < s.Length && char.IsLetter(s[k])) k++;
        return s[start..k].ToLowerInvariant();
    }

    // ── Letters and digits ────────────────────────────────────────────────────────────

    private static void AddAlphanumericCandidates(string s, List<Candidate> output)
    {
        int i = 0;
        while (i < s.Length)
        {
            if (!char.IsAsciiLetterOrDigit(s[i]))
            {
                i++;
                continue;
            }
            int start = i;
            while (i < s.Length && char.IsAsciiLetterOrDigit(s[i])) i++;
            int end = i;
            if (end - start is < MinAlphanumeric or > MaxAlphanumeric) continue;

            char before = start > 0 ? s[start - 1] : '\0';
            char after = end < s.Length ? s[end] : '\0';
            // Part of a longer word, a link, an address or an id like "AB12-CD34".
            if (char.IsLetterOrDigit(before) || char.IsLetterOrDigit(after)) continue;
            if (BadBefore.Contains(before) || BadAfter.Contains(after)) continue;
            if (before is '-' or '.' && start >= 2 && char.IsLetterOrDigit(s[start - 2])) continue;
            if (after is '-' or '.' && end + 1 < s.Length && char.IsLetterOrDigit(s[end + 1])) continue;

            string token = s[start..end];
            bool digit = false, upper = false, lower = false;
            foreach (char c in token)
            {
                if (char.IsAsciiDigit(c)) digit = true;
                else if (char.IsAsciiLetterUpper(c)) upper = true;
                else lower = true;
            }
            if (!digit || !upper || lower) continue;
            // Masked numbers ("XX1234") and quantities ("1500MAH", "128GB").
            if (token.StartsWith("XX", StringComparison.Ordinal) || QuantityToken().IsMatch(token)) continue;
            output.Add(new Candidate(start, end, token, Digits: false));
        }
    }

    // ── Characters ────────────────────────────────────────────────────────────────────

    private static bool IsSpace(char c) => c is ' ' or '\u00A0' or '\u202F' or '\u2009';

    private static bool IsHyphen(char c) => c is '-' or '\u2010' or '\u2011' or '\u2012' or '\u2013' or '\u2212';

    private static bool IsSeparator(char c) => IsSpace(c) || IsHyphen(c) || c is '.' or ',' or ':' or '/' or '\'' or '\u066B' or '\u066C';

    /// <summary>
    /// A letter of a script that writes spaces between words (Latin, Greek, Cyrillic). A code glued
    /// to such a letter is part of a word; next to Chinese, Japanese, Korean or Arabic letters it is not.
    /// </summary>
    private static bool IsWordLetter(char c) => char.IsLetter(c) && (c < '\u0530' || c is >= '\u1E00' and <= '\u1FFF');

    // ── Keywords ──────────────────────────────────────────────────────────────────────

    /// <summary>Words that on their own say "one-time code".</summary>
    [GeneratedRegex(
        @"(?<!\p{L})(?:otp|one[\s-]?time|2fa|two[\s-]?factor|2[\s-]?step|mfa|passcode|v[ée]rif\p{L}*|authenticat\p{L}*|authentifi\p{L}*|autentica\p{L}*|autentifica\p{L}*" +
        @"|(?:security|login|log[\s-]?in|sign[\s-]?in|signin|confirmation|access|auth|activation)[\s-]?codes?" +
        @"|best[äa]tigungs?code|sicherheitscode|anmeldecode|einmal(?:passwort|code|kennwort)|zugangscode|aktivierungscode|beveiligingscode|inlogcode|toegangscode" +
        @"|c[óo]digo\s+de\s+(?:seguridad|acceso|seguran[çc]a|acesso)|code\s+de\s+(?:s[ée]curit[ée]|connexion)|usage\s+unique|codice\s+di\s+(?:sicurezza|accesso)" +
        @"|weryfikac\p{L}*|jednorazow\p{L}*|do[ğg]rulama|tek\s+kullan[ıi]ml[ıi]k|pengesahan|x[áa]c\s+(?:minh|th[ựu]c)|подтвержд\p{L}*|одноразов\p{L}*|підтвердж\p{L}*)(?!\p{L})" +
        @"|验证码|驗證碼|校验码|校驗碼|动态码|動態碼|确认码|確認碼|认证码|認證碼|認証コード|確認コード|認証番号|確認番号|ワンタイム" +
        @"|인증\s?번호|인증\s?코드|확인\s?코드|보안\s?코드|ओटीपी|सत्यापन|التحقق|تأیید|تایید|รหัสยืนยัน|אימות",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex StrongKeywords();

    /// <summary>Words that often, not always, introduce a code.</summary>
    [GeneratedRegex(
        @"(?<!\p{L})(?:codes?|pin|password|passwort|kennwort|contrase[ñn]a|senha|mot\s+de\s+passe|clave|c[óo]digos?|codice|kod(?:e|en|u|unuz|y)?|kood|kodas|код|пароль|mã|[şs]ifre)(?!\p{L})" +
        @"|コード|코드|비밀번호|密码|密碼|暗証番号|代码|代碼|รหัส|कोड|رمز|كود|کد|קוד",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex WeakKeywords();

    /// <summary>Promotions, orders and addresses: with only a weak keyword there is no code.</summary>
    [GeneratedRegex(
        @"(?<!\p{L})(?:promo\p{L}*|coupon\p{L}*|discount\p{L}*|voucher\p{L}*|referral|invite|invitation|gift|tracking|track|zip(?:\s?code)?|postal|post\s?code" +
        @"|area\s+code|bar\s?code|qr\s+code|order|booking|reservation|pnr|sale|offer|deal|cup[óo]n\p{L}*|descuento|rabatt\p{L}*|gutschein\p{L}*|promoci[óo]n)(?!\p{L})",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex NegativeContext();

    [GeneratedRegex(
        @"(?<!\p{L})(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december" +
        @"|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre" +
        @"|janvier|f[ée]vrier|mars|avril|mai|juin|juillet|ao[ûu]t|octobre|novembre|d[ée]cembre" +
        @"|januar|februar|m[äa]rz|juni|juli|oktober|dezember|janeiro|fevereiro|mar[çc]o|maio|junho|julho|setembro|outubro|novembro|dezembro)\.?(?!\p{L})",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex MonthName();

    [GeneratedRegex(@"^[0-9]+[A-Z]{1,3}$")]
    private static partial Regex QuantityToken();
}
