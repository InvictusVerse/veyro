namespace Veyro.Data.Contracts;

/// <summary>
/// What the Photos page can do with a fetched original (ADR-034). Windows: Clipboard,
/// FileSavePicker, Launcher, DataTransferManager. Paths and names are user data: never logged.
/// </summary>
public interface IPhotoActions
{
    /// <summary>Puts the image on the clipboard as a bitmap.</summary>
    Task CopyAsync(string path);

    /// <summary>Asks where to save a copy. False when the user cancelled.</summary>
    Task<bool> SaveAsAsync(string path, string suggestedName);

    /// <summary>Opens the file in the default app for its type.</summary>
    Task<bool> OpenAsync(string path);

    /// <summary>Shows the Windows share sheet for the file.</summary>
    Task ShareAsync(string path, string title);
}
