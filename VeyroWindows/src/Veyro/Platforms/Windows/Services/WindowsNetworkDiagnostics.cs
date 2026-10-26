using System.Text;
using Veyro.Core.Diagnostics;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// Firewall and network-profile checks for the pairing screen (ADR-030). Reading needs no
/// elevation; adding the rule goes through the same UAC prompt Veyro shows at startup.
/// </summary>
public sealed class WindowsNetworkDiagnostics : INetworkDiagnosticsService
{
    public const string FirewallRuleName = "Veyro LAN Transfer";

    private const string AddRuleScript =
        "if (-not (Get-NetFirewallRule -DisplayName 'Veyro LAN Transfer' -ErrorAction SilentlyContinue)) { New-NetFirewallRule -DisplayName 'Veyro LAN Transfer' -Direction Inbound -Protocol TCP -LocalPort 5150-5169 -Action Allow -Profile Private }";

    private const string CheckScript =
        "$rule = [bool](Get-NetFirewallRule -DisplayName 'Veyro LAN Transfer' -ErrorAction SilentlyContinue | Where-Object { [string]$_.Enabled -eq 'True' });" +
        "$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object { [pscustomobject]@{ name = [string]$_.InterfaceAlias; category = [string]$_.NetworkCategory } });" +
        "[pscustomobject]@{ rule = $rule; profiles = $profiles } | ConvertTo-Json -Compress -Depth 3";

    private static readonly TimeSpan CheckTimeout = TimeSpan.FromSeconds(20);

    public async Task<NetworkCheckResult> CheckAsync()
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + Encode(CheckScript),
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            using Process? process = Process.Start(startInfo);
            if (process is null) return Unknown();
            using var timeout = new CancellationTokenSource(CheckTimeout);
            Task<string> output = process.StandardOutput.ReadToEndAsync(timeout.Token);
            _ = process.StandardError.ReadToEndAsync(timeout.Token);
            try { await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false); }
            catch (OperationCanceledException)
            {
                try { process.Kill(); } catch { }
                VeyroTrace.Warn("Network check timed out");
                return Unknown();
            }
            NetworkCheckResult result = Parse(await output.ConfigureAwait(false));
            VeyroTrace.Info($"Network check: firewall={result.Firewall}, profiles={result.Profiles.Count}, public={result.Profiles.Count(p => p.IsPublic)}");
            return result;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Network check failed", error);
            return Unknown();
        }
    }

    public async Task<bool> RequestFirewallRuleAsync()
    {
        try
        {
            VeyroTrace.Info("Requesting administrator permission to allow private-network TCP ports 5150-5169");
            var startInfo = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"{AddRuleScript}\"",
                Verb = "runas",
                UseShellExecute = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using Process? process = Process.Start(startInfo);
            if (process is null) { VeyroTrace.Warn($"Could not start firewall permission request for '{FirewallRuleName}'"); return false; }
            await process.WaitForExitAsync().ConfigureAwait(false);
            VeyroTrace.Info($"Firewall permission request exited with code {process.ExitCode}");
            return process.ExitCode == 0;
        }
        catch (Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            VeyroTrace.Warn("Firewall permission request was cancelled by the user");
            return false;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Firewall permission request failed", error);
            return false;
        }
    }

    private static NetworkCheckResult Parse(string json)
    {
        if (string.IsNullOrWhiteSpace(json)) return Unknown();
        using JsonDocument doc = JsonDocument.Parse(json);
        JsonElement root = doc.RootElement;
        FirewallRuleState firewall = root.TryGetProperty("rule", out JsonElement rule) && rule.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? (rule.GetBoolean() ? FirewallRuleState.Present : FirewallRuleState.Missing)
            : FirewallRuleState.Unknown;
        var profiles = new List<NetworkProfileInfo>();
        if (root.TryGetProperty("profiles", out JsonElement list))
        {
            IEnumerable<JsonElement> items = list.ValueKind switch
            {
                JsonValueKind.Array => list.EnumerateArray(),
                JsonValueKind.Object => [list],
                _ => []
            };
            foreach (JsonElement item in items)
            {
                string name = item.TryGetProperty("name", out JsonElement n) ? n.GetString() ?? "Network" : "Network";
                string category = item.TryGetProperty("category", out JsonElement c) ? c.GetString() ?? string.Empty : string.Empty;
                profiles.Add(new NetworkProfileInfo(name, string.Equals(category, "Public", StringComparison.OrdinalIgnoreCase)));
            }
        }
        return new NetworkCheckResult(firewall, profiles);
    }

    private static NetworkCheckResult Unknown() => new(FirewallRuleState.Unknown, []);

    private static string Encode(string script) => Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
}
