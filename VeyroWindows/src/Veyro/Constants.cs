namespace Veyro;

public static class Constants
{
    public const string AppDisplayName = "Veyro";
    public const string ProtocolVersionText = "VLP v1 (VTC1 TCP + WebSocket)";

    public static string LocalAppDataDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Veyro");

    public static string DownloadsDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Veyro");
}
