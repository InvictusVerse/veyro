using Veyro.Core.Diagnostics;
using Veyro.Core.Handoff;
using Veyro.Services;
using Windows.ApplicationModel.Activation;
using Windows.ApplicationModel.DataTransfer;
using Windows.ApplicationModel.DataTransfer.ShareTarget;
using Windows.Storage;

namespace Veyro.Platform.Handoff;

/// <summary>
/// Veyro as a Windows Share sheet target (manifest windows.shareTarget, StorageItems, ADR-039).
/// The process Windows activated keeps its ShareOperation: it reads the items, reports to
/// Windows, and hands plain file paths to the main instance (in-process, or over
/// <see cref="HandoffPipe"/> when another Veyro already runs). File names and paths are never logged.
/// </summary>
internal static class ShareTargetActivation
{
    private const string StagingFolderName = "ShareStaging";

    /// <summary>A second process: Veyro already runs; send the files over the pipe, then exit.</summary>
    public static Task RunInSecondaryProcessAsync(ShareTargetActivatedEventArgs args)
        => HandleAsync(args.ShareOperation,
            request => HandoffPipe.SendAsync(request, waitForResult: true, launchIfMissing: false, CancellationToken.None));

    /// <summary>The main instance (UI thread): Veyro was started, or reached, by the Share sheet.</summary>
    public static Task RunInMainInstanceAsync(ShareTargetActivatedEventArgs args)
        => HandleAsync(args.ShareOperation, HandoffCoordinator.HandleAsync);

    private static async Task HandleAsync(ShareOperation operation, Func<HandoffRequest, Task<HandoffReply>> deliver)
    {
        string? stagingDirectory = null;
        var staged = new List<string>();
        try
        {
            operation.ReportStarted();
            if (!operation.Data.Contains(StandardDataFormats.StorageItems))
            {
                operation.ReportError("Veyro can send files only.");
                return;
            }

            IReadOnlyList<IStorageItem> items = await operation.Data.GetStorageItemsAsync();
            var paths = new List<string>(items.Count);
            foreach (IStorageItem item in items)
            {
                // ADR-064: a folder on disk is sent whole; one with no real path is skipped.
                if (item is StorageFolder sharedFolder)
                {
                    if (!string.IsNullOrEmpty(sharedFolder.Path) && Directory.Exists(sharedFolder.Path)) paths.Add(sharedFolder.Path);
                    continue;
                }
                if (item is not StorageFile file) continue;
                if (!string.IsNullOrEmpty(file.Path) && File.Exists(file.Path))
                {
                    paths.Add(file.Path);
                    continue;
                }
                // No real path (a virtual or stream-backed item): copy it into LocalCache first.
                stagingDirectory ??= CreateStagingDirectory();
                StorageFolder folder = await StorageFolder.GetFolderFromPathAsync(stagingDirectory);
                StorageFile copy = await file.CopyAsync(folder, SafeFileName(file.Name), NameCollisionOption.GenerateUniqueName);
                staged.Add(copy.Path);
                paths.Add(copy.Path);
            }
            operation.ReportDataRetrieved();
            VeyroTrace.Info($"Share received {paths.Count} item(s), {staged.Count} staged");

            if (paths.Count == 0)
            {
                operation.ReportError("Veyro can send files and folders that are saved on this PC.");
                return;
            }

            HandoffReply reply = await deliver(new HandoffRequest(HandoffKind.Share, paths));
            if (reply.Outcome is HandoffOutcome.Sent or HandoffOutcome.Cancelled) operation.ReportCompleted();
            else operation.ReportError(reply.Message ?? "Veyro could not send the files.");
        }
        catch (Exception error)
        {
            // Type only: messages can name the shared files.
            VeyroTrace.Warn($"Share target failed: {error.GetType().Name}");
            try { operation.ReportError("Veyro could not send the files. Open Veyro and try again."); }
            catch (Exception) { /* the share sheet is already gone */ }
        }
        finally
        {
            DeleteStaged(staged, stagingDirectory);
        }
    }

    private static string StagingRoot => Path.Combine(Constants.LocalCacheDirectory, StagingFolderName);

    private static string CreateStagingDirectory()
    {
        string directory = Path.Combine(StagingRoot, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    /// <summary>Removes staged copies a crash left behind. Main instance startup, best effort.</summary>
    public static void CleanStaleStaging()
    {
        try
        {
            if (!Directory.Exists(StagingRoot)) return;
            DateTime cutoff = DateTime.UtcNow.AddHours(-1);
            foreach (string directory in Directory.EnumerateDirectories(StagingRoot))
            {
                if (Directory.GetLastWriteTimeUtc(directory) > cutoff) continue;
                DeleteStaged(Directory.EnumerateFiles(directory).ToList(), directory);
            }
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Cleaning share staging failed: {error.GetType().Name}");
        }
    }

    private static void DeleteStaged(IEnumerable<string> files, string? directory)
    {
        foreach (string file in files)
        {
            try { File.Delete(file); }
            catch (Exception) { /* still open somewhere; the next start removes it */ }
        }
        if (directory is null) return;
        try { Directory.Delete(directory); }
        catch (Exception) { /* not empty yet */ }
    }

    private static string SafeFileName(string name)
    {
        string candidate = Path.GetFileName(name ?? string.Empty);
        foreach (char invalid in Path.GetInvalidFileNameChars()) candidate = candidate.Replace(invalid, '_');
        candidate = candidate.Trim().TrimEnd('.');
        return string.IsNullOrEmpty(candidate) ? "shared-file" : candidate;
    }
}
