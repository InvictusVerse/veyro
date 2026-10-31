using System.Collections.Specialized;
using System.ComponentModel;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using Veyro.Core.Diagnostics;
using Veyro.Core.Handoff;
using Veyro.Core.Models;
using Veyro.Services;

namespace Veyro.Platform.Handoff;

/// <summary>
/// ADR-076: the local bridge for the Islune dynamic island. A named pipe for the current user only
/// (<see cref="PipeOptions.CurrentUserOnly"/>) that accepts only a client whose executable is
/// <c>islune.exe</c>. It lets the island show the paired phones and the running transfer, and send
/// text (the "Send clipboard" path) or files (the normal hand-off send path). The trust boundary is
/// the user's session, as for the Explorer menu: nothing here can pair, read phone data or receive.
/// Frames: <see cref="IslandBridgeCodec"/>. Text, paths and names are never logged.
/// </summary>
internal sealed class IslandBridgeServer : IDisposable
{
    public static string PipeName { get; } = "Veyro.Island." + (WindowsIdentity.GetCurrent().User?.Value ?? "user");

    private const int MaxInstances = 4;
    private const string ClientExecutable = "islune.exe";
    private static readonly TimeSpan RequestReadTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan StatusSpacing = TimeSpan.FromMilliseconds(250);

    private readonly DispatcherQueue _ui;
    private readonly CancellationTokenSource _stop = new();
    private readonly object _gate = new();
    private byte[] _status = IslandBridgeCodec.EncodeStatus(new IslandStatus([], null));
    private TaskCompletionSource _changed = NewSignal();
    private bool _recomputeQueued;

    public IslandBridgeServer(DispatcherQueue ui) => _ui = ui;

    /// <summary>UI thread, once.</summary>
    public void Start()
    {
        var container = VeyroContainer.Instance;
        container.DeviceManager.ConnectionChanged += _ => QueueRecompute();
        container.DeviceManager.ActiveDeviceChanged += _ => QueueRecompute();
        container.DeviceManager.PairedDevices.CollectionChanged += OnDevicesChanged;
        foreach (var device in container.DeviceManager.PairedDevices) device.PropertyChanged += OnDeviceChanged;
        container.FileTransferService.ActiveTransferChanged += _ => QueueRecompute();
        Recompute();
        _ = Task.Run(AcceptLoopAsync);
    }

    private void OnDevicesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        foreach (var device in e.NewItems?.OfType<INotifyPropertyChanged>() ?? []) device.PropertyChanged += OnDeviceChanged;
        foreach (var device in e.OldItems?.OfType<INotifyPropertyChanged>() ?? []) device.PropertyChanged -= OnDeviceChanged;
        QueueRecompute();
    }

    private void OnDeviceChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(Data.Models.PairedDeviceUiModel.Name) or nameof(Data.Models.PairedDeviceUiModel.IsConnected))
            QueueRecompute();
    }

    /// <summary>Any thread: the snapshot is rebuilt once on the UI thread, where the models live.</summary>
    private void QueueRecompute()
    {
        lock (_gate)
        {
            if (_recomputeQueued) return;
            _recomputeQueued = true;
        }
        if (!_ui.TryEnqueue(Recompute))
        {
            lock (_gate) _recomputeQueued = false;
        }
    }

    private void Recompute()
    {
        lock (_gate) _recomputeQueued = false;
        try
        {
            var container = VeyroContainer.Instance;
            var devices = container.DeviceManager.PairedDevices
                .Select(device => new IslandDevice(device.DeviceId, device.Name, device.IsConnected))
                .ToList();
            IslandTransfer? transfer = container.FileTransferService.ActiveTransfer is { } active
                ? new IslandTransfer(active.IsIncoming, active.Stage.ToString(), (int)Math.Round(active.ProgressFraction * 1000),
                    active.PeerDisplayName)
                : null;
            byte[] status = IslandBridgeCodec.EncodeStatus(new IslandStatus(devices, transfer));
            TaskCompletionSource changed;
            lock (_gate)
            {
                if (status.AsSpan().SequenceEqual(_status)) return;
                _status = status;
                changed = _changed;
                _changed = NewSignal();
            }
            changed.TrySetResult();
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Island status failed: {error.GetType().Name}");
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = new NamedPipeServerStream(
                    PipeName, PipeDirection.InOut, MaxInstances, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                try { await Task.Delay(1000, _stop.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
                continue;
            }
            try
            {
                await pipe.WaitForConnectionAsync(_stop.Token).ConfigureAwait(false);
            }
            catch (Exception)
            {
                await pipe.DisposeAsync().ConfigureAwait(false);
                if (_stop.IsCancellationRequested) return;
                continue;
            }
            _ = Task.Run(() => ServeAsync(pipe));
        }
    }

    private async Task ServeAsync(NamedPipeServerStream pipe)
    {
        await using (pipe.ConfigureAwait(false))
        {
            try
            {
                if (!HandoffPipe.GetNamedPipeClientProcessId(pipe.SafePipeHandle, out uint clientPid) || !IsIslune(clientPid))
                {
                    VeyroTrace.Warn("Island bridge refused: the caller is not Islune");
                    return;
                }
                IslandRequest request;
                using (var readTimeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token))
                {
                    readTimeout.CancelAfter(RequestReadTimeout);
                    request = IslandBridgeCodec.DecodeRequest(await HandoffCodec.ReadFrameAsync(pipe, readTimeout.Token).ConfigureAwait(false));
                }
                switch (request.Op)
                {
                    case IslandOp.Status:
                        await StreamStatusAsync(pipe).ConfigureAwait(false);
                        break;
                    case IslandOp.Text:
                        await ReplyAsync(pipe, await SendTextAsync(request.Text!).ConfigureAwait(false)).ConfigureAwait(false);
                        break;
                    case IslandOp.Files:
                        await ReplyAsync(pipe, await SendFilesAsync(request).ConfigureAwait(false)).ConfigureAwait(false);
                        break;
                }
            }
            catch (FormatException)
            {
                VeyroTrace.Warn("Island bridge refused: malformed request");
            }
            catch (Exception error) when (error is IOException or OperationCanceledException or ObjectDisposedException)
            {
                // The island closed the connection or Veyro is shutting down.
            }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Island bridge failed: {error.GetType().Name}");
            }
        }
    }

    /// <summary>The current status now, then again on every change (at most four a second) until the island goes away.</summary>
    private async Task StreamStatusAsync(NamedPipeServerStream pipe)
    {
        var closed = new byte[1];
        Task<int> gone = pipe.ReadAsync(closed, 0, 1, _stop.Token);
        while (true)
        {
            byte[] status;
            Task changed;
            lock (_gate)
            {
                status = _status;
                changed = _changed.Task;
            }
            await HandoffCodec.WriteFrameAsync(pipe, status, _stop.Token).ConfigureAwait(false);
            if (await Task.WhenAny(changed, gone).ConfigureAwait(false) == gone) return;
            if (await Task.WhenAny(Task.Delay(StatusSpacing, _stop.Token), gone).ConfigureAwait(false) == gone) return;
        }
    }

    private static async Task<IslandResult> SendTextAsync(string text)
    {
        bool sent = await VeyroContainer.Instance.Clipboard.SendTextNowAsync(text).ConfigureAwait(false);
        return sent
            ? new IslandResult(true, "Text sent to your phone.")
            : new IslandResult(false, "No connected phone takes clipboard text. Open Veyro on the phone.");
    }

    private static async Task<IslandResult> SendFilesAsync(IslandRequest request)
    {
        var handoff = request.DeviceId is null
            ? new HandoffRequest(HandoffKind.ContextMenu, request.Paths, HandoffTargetKind.AllConnected)
            : new HandoffRequest(HandoffKind.ContextMenu, request.Paths, HandoffTargetKind.Device, request.DeviceId);
        HandoffReply reply = await HandoffCoordinator.HandleFromAnyThreadAsync(handoff).ConfigureAwait(false);
        return reply.Outcome switch
        {
            HandoffOutcome.Sent => new IslandResult(true, "Sent."),
            HandoffOutcome.Cancelled => new IslandResult(false, "Cancelled."),
            _ => new IslandResult(false, reply.Message ?? "Sending failed. Open Veyro to see why.")
        };
    }

    private async Task ReplyAsync(NamedPipeServerStream pipe, IslandResult result)
    {
        try
        {
            await HandoffCodec.WriteFrameAsync(pipe, IslandBridgeCodec.EncodeResult(result), _stop.Token).ConfigureAwait(false);
        }
        catch (IOException)
        {
            // The island stopped waiting; the transfer status still reaches it.
        }
    }

    /// <summary>
    /// The client's image is islune.exe. A name check, not a signature: it keeps other programs from
    /// using this pipe by accident; the boundary is the user's session (ADR-076).
    /// </summary>
    private static bool IsIslune(uint pid)
    {
        nint process = 0;
        try
        {
            process = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (process == 0) return false;
            var buffer = new char[32768];
            int length = buffer.Length;
            if (!QueryFullProcessImageName(process, 0, buffer, ref length)) return false;
            return string.Equals(Path.GetFileName(new string(buffer, 0, length)), ClientExecutable, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            if (process != 0) _ = CloseHandle(process);
        }
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
    }

    private const uint ProcessQueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern nint OpenProcess(uint desiredAccess, [MarshalAs(UnmanagedType.Bool)] bool inheritHandle, uint processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "QueryFullProcessImageNameW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryFullProcessImageName(nint process, uint flags, [Out] char[] exeName, ref int size);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(nint handle);
}
