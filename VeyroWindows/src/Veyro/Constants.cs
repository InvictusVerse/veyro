namespace Veyro;

public static class Constants
{
    public const string AppDisplayName = "Veyro";
    public const string ProtocolVersionText = "VLP link v3 (VTC3 TCP + WebSocket)";

    public static string LocalAppDataDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Veyro");

    /// <summary>
    /// The package's LocalCache folder (ApplicationData.LocalCacheFolder), which Windows never
    /// roams or backs up. Unpackaged runs fall back to a Cache folder beside the app data.
    /// </summary>
    public static string LocalCacheDirectory
    {
        get
        {
            try { return Windows.Storage.ApplicationData.Current.LocalCacheFolder.Path; }
            catch (Exception) { return Path.Combine(LocalAppDataDirectory, "Cache"); }
        }
    }

    public static string DownloadsDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Veyro");
}
