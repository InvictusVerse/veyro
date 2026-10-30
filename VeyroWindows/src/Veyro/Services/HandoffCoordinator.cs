using Veyro.Core.Diagnostics;
using Veyro.Core.Handoff;
using Veyro.Dialogs;

namespace Veyro.Services;

/// <summary>
/// The main instance's side of a hand-off (ADR-039): files from the Share sheet or the Explorer
/// menu are sent to the chosen phones through the normal send path
/// (<see cref="IFileTransferService.SendFilesAsync"/>), one phone after another, without a file
/// picker. The Share sheet may ask "Send to" first (<see cref="SendTargetSelector"/>). Handled one
/// at a time on the UI thread. Paths and file names are never logged.
/// </summary>
internal static class HandoffCoordinator
{
    private const string GenericFailure = "Sending failed. Open Veyro to see why.";
    private static readonly TimeSpan ConnectWait = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan WindowWait = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan DialogWait = TimeSpan.FromSeconds(90);
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static DispatcherQueue? _ui;

    /// <summary>Call once on the UI thread at startup.</summary>
    public static void Initialize(DispatcherQueue ui) => _ui = ui;

    /// <summary>From the pipe or the Explorer menu (any thread): runs <see cref="HandleAsync"/> on the UI thread.</summary>
    public static Task<HandoffReply> HandleFromAnyThreadAsync(HandoffRequest request)
    {
        DispatcherQueue? ui = _ui;
        if (ui is null) return Task.FromResult(new HandoffReply(HandoffOutcome.Failed, "Veyro is still starting. Try again in a moment."));
        if (ui.HasThreadAccess) return HandleAsync(request);

        var completion = new TaskCompletionSource<HandoffReply>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!ui.TryEnqueue(async () => completion.TrySetResult(await HandleAsync(request))))
            completion.TrySetResult(new HandoffReply(HandoffOutcome.Failed, "Veyro is closing."));
        return completion.Task;
    }

    /// <summary>UI thread. Never throws.</summary>
    public static async Task<HandoffReply> HandleAsync(HandoffRequest request)
    {
        await Gate.WaitAsync();
        try
        {
            return await HandleCoreAsync(request);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Hand-off handling failed: {error.GetType().Name}");
            return new HandoffReply(HandoffOutcome.Failed, GenericFailure);
        }
        finally
        {
            Gate.Release();
        }
    }

    private static async Task<HandoffReply> HandleCoreAsync(HandoffRequest request)
    {
        if (!await WaitForMainWindowAsync()) return new HandoffReply(HandoffOutcome.Failed, "Veyro is still starting. Try again in a moment.");
        var container = VeyroContainer.Instance;

        // ADR-047: sending does not open Veyro. The window comes up only when it has to ask
        // something (pair a phone, which phone) or for pairing itself; otherwise files go in the
        // background and a toast says how it went.
        if (request.Kind == HandoffKind.Pair)
        {
            App.ShowMainWindow();
            container.MainPageViewModel.RequestPairing();
            return new HandoffReply(HandoffOutcome.Sent);
        }

        // Files and folders that still exist (ADR-064: a folder is sent whole); a vanished one is skipped.
        List<string> files = request.Paths.Where(path => File.Exists(path) || Directory.Exists(path))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (files.Count == 0) return await FailVisiblyAsync(request, "The files are no longer available.");

        List<SendCandidate> paired = container.DeviceManager.PairedDevices
            .Select(device => new SendCandidate(device.DeviceId, device.Name, device.IsConnected))
            .ToList();
        SendPlan plan = SendTargetSelector.Plan(
            paired,
            container.DeviceManager.ActiveDevice?.DeviceId,
            request.Target,
            request.DeviceId,
            container.SettingsStore.Current.SendSharedFilesStraightToConnectedPhone);
        VeyroTrace.Info($"Hand-off {request.Kind}: {files.Count} file(s), plan {plan.GetType().Name}");

        IReadOnlyList<string> targets;
        switch (plan)
        {
            case SendPlan.NoPhonePaired:
                App.ShowMainWindow();
                container.MainPageViewModel.RequestPairing();
                return new HandoffReply(HandoffOutcome.NoPhonePaired, "Pair a phone with Veyro first.");
            case SendPlan.Unavailable unavailable:
                return await FailVisiblyAsync(request, unavailable.Message);
            case SendPlan.SendNow now:
                targets = now.DeviceIds;
                break;
            case SendPlan.Choose choose:
                SendToDialog.Choice? choice = await AskAsync(paired, choose.PreselectedDeviceId, files);
                if (choice is null) return new HandoffReply(HandoffOutcome.Cancelled);
                targets = SendTargetSelector.ResolveChoice(paired, choice.Value.DeviceId, choice.Value.AllConnected);
                if (targets.Count == 0) return new HandoffReply(HandoffOutcome.Failed, SendTargetSelector.NothingConnectedMessage);
                break;
            default:
                return new HandoffReply(HandoffOutcome.Failed, GenericFailure);
        }

        string what = files.Count switch
        {
            1 when Directory.Exists(files[0]) => $"the {Path.GetFileName(Path.TrimEndingDirectorySeparator(files[0]))} folder",
            1 => Path.GetFileName(files[0]),
            _ when files.Any(Directory.Exists) => $"{files.Count} items",
            _ => $"{files.Count} files"
        };
        string names = string.Join(", ", targets.Select(id => Find(container, id)?.Name ?? "your phone"));
        // While the window is open the transfer banner shows progress; otherwise a toast does.
        bool toast = !App.WindowOnScreen;
        if (toast) container.NotificationToastService.ShowStatus($"Sending {what}", $"To {names}", tag: "handoff");

        int sent = 0;
        foreach (string deviceId in targets)
        {
            if (await SendToAsync(container, deviceId, files)) sent++;
        }
        VeyroTrace.Info($"Hand-off sent to {sent} of {targets.Count} phone(s)");
        HandoffReply reply = sent == targets.Count
            ? new HandoffReply(HandoffOutcome.Sent)
            : sent > 0
                ? new HandoffReply(HandoffOutcome.PartlySent, $"Sent to {sent} of {targets.Count} phones. Open Veyro to see why.")
                : new HandoffReply(HandoffOutcome.Failed, GenericFailure);
        if (toast)
        {
            if (reply.Outcome == HandoffOutcome.Sent) container.NotificationToastService.ShowStatus($"Sent {what}", $"To {names}", tag: "handoff");
            else container.NotificationToastService.ShowStatus($"Could not send {what}", reply.Message ?? GenericFailure, tag: "handoff");
        }
        return reply;
    }

    /// <summary>
    /// One phone through the normal send path. Waits for a running transfer (one at a time) and,
    /// briefly, for a paired phone that is not connected yet (Veyro may just have started).
    /// </summary>
    private static async Task<bool> SendToAsync(VeyroContainer container, string deviceId, IReadOnlyList<string> files)
    {
        IFileTransferService transfers = container.FileTransferService;
        while (transfers.ActiveTransfer is { } running && !IsFinished(running)) await Task.Delay(250);

        if (Find(container, deviceId) is { IsConnected: false })
        {
            var waited = Stopwatch.StartNew();
            while (waited.Elapsed < ConnectWait && Find(container, deviceId) is { IsConnected: false }) await Task.Delay(250);
        }
        // The list may have been rebuilt meanwhile: look the phone up again.
        if (Find(container, deviceId) is not { } device) return false;

        await transfers.SendFilesAsync(device, files);
        return transfers.ActiveTransfer is { Stage: TransferStage.Completed } done &&
               string.Equals(done.PeerDeviceId, deviceId, StringComparison.OrdinalIgnoreCase);
    }

    private static async Task<SendToDialog.Choice?> AskAsync(IReadOnlyList<SendCandidate> paired, string? preselected, IReadOnlyList<string> files)
    {
        // One ContentDialog per window (ADR-036): wait for a consent or pairing prompt to close.
        var waited = Stopwatch.StartNew();
        while (!App.TryEnterDialog())
        {
            if (waited.Elapsed > DialogWait) return null;
            await Task.Delay(200);
        }
        try
        {
            // A folder counts its files (ADR-064); one too big to send shows no totals here and
            // says so when the send starts.
            OutgoingPlan? plan = null;
            try { plan = await Task.Run(() => OutgoingPlanner.Build(files)); }
            catch (FolderTooLargeException) { }
            // The one case that needs the window: the user picks the phone.
            XamlRoot? root = await App.ShowMainWindowForDialogAsync();
            if (root is null) return null;
            var dialog = new SendToDialog(paired, preselected, plan?.FileCount ?? files.Count, plan?.TotalBytes ?? 0) { XamlRoot = root };
            return await dialog.AskAsync();
        }
        finally
        {
            App.ExitDialog();
        }
    }

    /// <summary>
    /// The Share sheet shows the reply's message itself; the Explorer menu has no such place, so a
    /// toast says it (ADR-047: no window just to report a failure).
    /// </summary>
    private static Task<HandoffReply> FailVisiblyAsync(HandoffRequest request, string message)
    {
        if (request.Kind == HandoffKind.ContextMenu)
            VeyroContainer.Instance.NotificationToastService.ShowStatus("Send with Veyro", message, tag: "handoff");
        return Task.FromResult(new HandoffReply(HandoffOutcome.Failed, message));
    }

    /// <summary>The main window object exists (it may be hidden in the tray).</summary>
    private static async Task<bool> WaitForMainWindowAsync()
    {
        var waited = Stopwatch.StartNew();
        while (App.MainWindow?.Content is null)
        {
            if (waited.Elapsed > WindowWait) return false;
            await Task.Delay(100);
        }
        return true;
    }

    private static PairedDeviceUiModel? Find(VeyroContainer container, string deviceId)
        => container.DeviceManager.PairedDevices.FirstOrDefault(device => string.Equals(device.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));

    private static bool IsFinished(ActiveTransfer transfer)
        => transfer.Stage is TransferStage.Completed or TransferStage.Failed or TransferStage.Cancelled;
}
