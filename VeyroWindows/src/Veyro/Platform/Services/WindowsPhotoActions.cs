using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using Windows.System;
using WinRT.Interop;

namespace Veyro.Platform.Services;

/// <summary>
/// Copy, Save as, Open and Share for a fetched photo (ADR-034). The window handle is read when an
/// action runs, since the main window is created after the container. Paths, names and image
/// bytes are user data: failures log the exception type only.
/// </summary>
public sealed class WindowsPhotoActions(Func<nint> windowHandle) : IPhotoActions
{
    private StorageFile? _shareFile;
    private string _shareTitle = string.Empty;
    private DataTransferManager? _shareManager;

    public async Task CopyAsync(string path)
    {
        StorageFile file = await StorageFile.GetFileFromPathAsync(path);
        var package = new DataPackage { RequestedOperation = DataPackageOperation.Copy };
        package.SetBitmap(RandomAccessStreamReference.CreateFromFile(file));
        Clipboard.SetContent(package);
        // Keep the bitmap on the clipboard after the cached original is deleted or Veyro exits.
        try { Clipboard.Flush(); }
        catch (Exception error) { VeyroTrace.Warn($"Clipboard flush failed: {error.GetType().Name}"); }
    }

    public async Task<bool> SaveAsAsync(string path, string suggestedName)
    {
        StorageFile source = await StorageFile.GetFileFromPathAsync(path);
        string extension = Path.GetExtension(suggestedName);
        if (string.IsNullOrEmpty(extension)) extension = Path.GetExtension(path);
        if (string.IsNullOrEmpty(extension)) extension = ".jpg";
        var picker = new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.PicturesLibrary,
            SuggestedFileName = Path.GetFileNameWithoutExtension(suggestedName)
        };
        picker.FileTypeChoices.Add("Image", [extension]);
        InitializeWithWindow.Initialize(picker, windowHandle());
        StorageFile? target = await picker.PickSaveFileAsync();
        if (target is null) return false;
        await source.CopyAndReplaceAsync(target);
        return true;
    }

    public async Task<bool> OpenAsync(string path)
    {
        StorageFile file = await StorageFile.GetFileFromPathAsync(path);
        return await Launcher.LaunchFileAsync(file);
    }

    public async Task ShareAsync(string path, string title)
    {
        _shareFile = await StorageFile.GetFileFromPathAsync(path);
        _shareTitle = string.IsNullOrWhiteSpace(title) ? "Photo" : title;
        nint hwnd = windowHandle();
        if (_shareManager is null)
        {
            _shareManager = DataTransferManagerInterop.GetForWindow(hwnd);
            _shareManager.DataRequested += OnDataRequested;
        }
        DataTransferManagerInterop.ShowShareUIForWindow(hwnd);
    }

    private void OnDataRequested(DataTransferManager sender, DataRequestedEventArgs args)
    {
        if (_shareFile is not { } file) return;
        args.Request.Data.Properties.Title = _shareTitle;
        args.Request.Data.SetStorageItems([file]);
    }
}
