using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;
using Veyro.Core.Diagnostics;
using Veyro.Core.Handoff;

namespace Veyro.Platform.Handoff;

/// <summary>
/// Local hand-off between Veyro processes (ADR-039): a Share activation that arrived in a second
/// process, or the Explorer menu server, gives file paths to the running Veyro. A named pipe
/// restricted to the current user (<see cref="PipeOptions.CurrentUserOnly"/>), and both ends check
/// that the other one is this same Veyro.exe. Frames: <see cref="HandoffCodec"/>. The running Veyro
/// answers <see cref="HandoffOutcome.Accepted"/> as soon as it has read the request, then the result.
/// </summary>
internal static class HandoffPipe
{
    private static readonly TimeSpan LaunchConnectTimeout = TimeSpan.FromSeconds(30);

    /// <summary>Per user, so another account's Veyro is never reached.</summary>
    public static string PipeName { get; } = "Veyro.Handoff." + (WindowsIdentity.GetCurrent().User?.Value ?? "user");

    /// <summary>
    /// Sends <paramref name="request"/> to the running Veyro. With <paramref name="launchIfMissing"/>
    /// Veyro is started when nobody listens. Returns the final result when
    /// <paramref name="waitForResult"/>, else the Accepted reply. Throws when Veyro cannot be reached.
    /// </summary>
    public static async Task<HandoffReply> SendAsync(HandoffRequest request, bool waitForResult, bool launchIfMissing, CancellationToken ct)
    {
        byte[] payload = HandoffCodec.EncodeRequest(request);
        await using NamedPipeClientStream pipe = await ConnectAsync(launchIfMissing, ct).ConfigureAwait(false);

        if (GetNamedPipeServerProcessId(pipe.SafePipeHandle, out uint serverPid))
        {
            if (!IsSameExecutable(serverPid)) throw new IOException("The hand-off pipe is not served by Veyro.");
            // The chooser or the pairing screen comes up in that process; let it take the foreground.
            _ = AllowSetForegroundWindow(serverPid);
        }

        await HandoffCodec.WriteFrameAsync(pipe, payload, ct).ConfigureAwait(false);
        HandoffReply accepted = HandoffCodec.DecodeReply(await HandoffCodec.ReadFrameAsync(pipe, ct).ConfigureAwait(false));
        if (!waitForResult || accepted.Outcome != HandoffOutcome.Accepted) return accepted;
        // No timeout: the user may take a while in the chooser, and big files take a while to send.
        return HandoffCodec.DecodeReply(await HandoffCodec.ReadFrameAsync(pipe, ct).ConfigureAwait(false));
    }

    private static async Task<NamedPipeClientStream> ConnectAsync(bool launchIfMissing, CancellationToken ct)
    {
        var pipe = NewClient();
        try
        {
            await pipe.ConnectAsync(400, ct).ConfigureAwait(false);
            return pipe;
        }
        catch (TimeoutException)
        {
            await pipe.DisposeAsync().ConfigureAwait(false);
        }

        if (launchIfMissing) LaunchVeyro();

        // Veyro may be starting (or was just started): its pipe opens once the window is up.
        var deadline = DateTime.UtcNow + LaunchConnectTimeout;
        while (true)
        {
            pipe = NewClient();
            try
            {
                await pipe.ConnectAsync(1000, ct).ConfigureAwait(false);
                return pipe;
            }
            catch (TimeoutException) when (DateTime.UtcNow < deadline)
            {
                await pipe.DisposeAsync().ConfigureAwait(false);
            }
            catch
            {
                await pipe.DisposeAsync().ConfigureAwait(false);
                throw;
            }
        }
    }

    private static NamedPipeClientStream NewClient()
        => new(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);

    /// <summary>Starts this package's Veyro.exe as a normal launch; it becomes the main instance.</summary>
    private static void LaunchVeyro()
    {
        try
        {
            string? exe = Environment.ProcessPath;
            if (exe is null) return;
            using var _ = Process.Start(new ProcessStartInfo(exe) { UseShellExecute = false });
            VeyroTrace.Info("Hand-off started Veyro");
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Starting Veyro for a hand-off failed: {error.GetType().Name}");
        }
    }

    /// <summary>True when <paramref name="pid"/> runs this same executable (same package, same path).</summary>
    public static bool IsSameExecutable(uint pid)
    {
        nint process = 0;
        try
        {
            string? ownPath = Environment.ProcessPath;
            if (ownPath is null) return false;
            process = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (process == 0) return false;
            var buffer = new char[32768];
            int length = buffer.Length;
            if (!QueryFullProcessImageName(process, 0, buffer, ref length)) return false;
            string otherPath = new(buffer, 0, length);
            return string.Equals(Path.GetFullPath(otherPath), Path.GetFullPath(ownPath), StringComparison.OrdinalIgnoreCase);
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

    private const uint ProcessQueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern nint OpenProcess(uint desiredAccess, [MarshalAs(UnmanagedType.Bool)] bool inheritHandle, uint processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "QueryFullProcessImageNameW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryFullProcessImageName(nint process, uint flags, [Out] char[] exeName, ref int size);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(nint handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetNamedPipeClientProcessId(SafePipeHandle pipe, out uint clientProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint serverProcessId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AllowSetForegroundWindow(uint processId);
}

/// <summary>
/// The running Veyro's end of <see cref="HandoffPipe"/>. Requests from any process other than this
/// Veyro.exe are dropped unread.
/// </summary>
internal sealed class HandoffPipeServer : IDisposable
{
    private const int MaxInstances = 4;
    private static readonly TimeSpan RequestReadTimeout = TimeSpan.FromSeconds(10);

    private readonly Func<HandoffRequest, Task<HandoffReply>> _handle;
    private readonly CancellationTokenSource _stop = new();

    public HandoffPipeServer(Func<HandoffRequest, Task<HandoffReply>> handle) => _handle = handle;

    public void Start() => _ = Task.Run(AcceptLoopAsync);

    private async Task AcceptLoopAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = new NamedPipeServerStream(
                    HandoffPipe.PipeName, PipeDirection.InOut, MaxInstances, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                // Every instance busy, or the name is held elsewhere: try again shortly.
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
                if (!HandoffPipe.GetNamedPipeClientProcessId(pipe.SafePipeHandle, out uint clientPid) ||
                    !HandoffPipe.IsSameExecutable(clientPid))
                {
                    VeyroTrace.Warn("Hand-off refused: the caller is not Veyro");
                    return;
                }

                HandoffRequest request;
                using (var readTimeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token))
                {
                    readTimeout.CancelAfter(RequestReadTimeout);
                    request = HandoffCodec.DecodeRequest(await HandoffCodec.ReadFrameAsync(pipe, readTimeout.Token).ConfigureAwait(false));
                }
                await HandoffCodec.WriteFrameAsync(pipe, HandoffCodec.EncodeReply(new HandoffReply(HandoffOutcome.Accepted)), _stop.Token)
                    .ConfigureAwait(false);

                HandoffReply reply = await _handle(request).ConfigureAwait(false);
                try
                {
                    await HandoffCodec.WriteFrameAsync(pipe, HandoffCodec.EncodeReply(reply), _stop.Token).ConfigureAwait(false);
                }
                catch (IOException)
                {
                    // The Explorer menu server does not wait for the result.
                }
            }
            catch (FormatException)
            {
                VeyroTrace.Warn("Hand-off refused: malformed request");
            }
            catch (Exception error)
            {
                // Type only: messages can carry paths.
                VeyroTrace.Warn($"Hand-off failed: {error.GetType().Name}");
            }
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
    }
}
