using System.Net;
using System.Net.Sockets;
using Veyro.Core.Diagnostics;
using Veyro.Core.Link;
using Veyro.Core.Protocol.Media;

namespace Veyro.Core.Mirroring;

/// <summary>An authenticated VMS1 connection: the socket and its sealed channel. Dispose closes both.</summary>
public sealed class Vms1Connection(TcpClient client, Vms1Channel channel) : IDisposable
{
    public Vms1Channel Channel { get; } = channel;

    public void Dispose()
    {
        Channel.Dispose();
        client.Dispose();
    }
}

/// <summary>
/// Listens for the one VMS1 connection of a pending stream session (04-PROTOCOL.md 7.5). Binds a
/// free port in the firewall-allowed 5150-5169 range, accepts at most
/// <see cref="MaxUnauthenticated"/> handshakes at a time, gives each <see cref="HandshakeTimeout"/>,
/// and stops listening at the first authenticated connection or after <see cref="WaitTimeout"/>.
/// A connection for another session, other keys or another phone is closed without an answer.
/// </summary>
public sealed class Vms1Listener : IAsyncDisposable
{
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan WaitTimeout = TimeSpan.FromSeconds(75);
    public const int MaxUnauthenticated = 4;

    private readonly TcpListener _listener;
    private readonly string _pcId;
    private readonly byte[] _sid;
    private readonly Vms1PendingSession _session;
    private readonly CancellationTokenSource _stop = new();
    private readonly TaskCompletionSource<Vms1Connection?> _accepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly SemaphoreSlim _slots = new(MaxUnauthenticated, MaxUnauthenticated);

    private Vms1Listener(TcpListener listener, string pcId, byte[] sid, Vms1PendingSession session)
    {
        _listener = listener;
        _pcId = pcId;
        _sid = sid;
        _session = session;
    }

    /// <summary>The port the phone dials (<c>streamStart.port</c>).</summary>
    public int Port { get; private init; }

    /// <summary>Starts listening for session <paramref name="sid"/>. <paramref name="address"/> is for tests (loopback).</summary>
    public static Vms1Listener Start(string pcId, byte[] sid, Vms1PendingSession session, IPAddress? address = null,
        int portStart = LinkServer.PortRangeStart, int portEnd = LinkServer.PortRangeEnd, TimeSpan? waitTimeout = null)
    {
        if (sid.Length != Vms1.SidSize) throw new ArgumentException("VMS1 sid is 16 bytes.", nameof(sid));
        for (int port = portStart; port <= portEnd; port++)
        {
            var listener = new TcpListener(address ?? IPAddress.Any, port);
            try
            {
                listener.Start(MaxUnauthenticated);
            }
            catch (SocketException)
            {
                continue;
            }
            var vms = new Vms1Listener(listener, pcId, sid, session) { Port = ((IPEndPoint)listener.LocalEndpoint).Port };
            vms._stop.CancelAfter(waitTimeout ?? WaitTimeout);
            _ = vms.AcceptLoopAsync();
            return vms;
        }
        throw new IOException($"No free media port in {portStart}-{portEnd}.");
    }

    private async Task AcceptLoopAsync()
    {
        CancellationToken ct = _stop.Token;
        try
        {
            while (!ct.IsCancellationRequested && !_accepted.Task.IsCompleted)
            {
                await _slots.WaitAsync(ct).ConfigureAwait(false);
                TcpClient client;
                try
                {
                    client = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                }
                catch
                {
                    _slots.Release();
                    throw;
                }
                _ = HandshakeAsync(client, ct);
            }
        }
        catch (Exception error) when (error is OperationCanceledException or SocketException or ObjectDisposedException)
        {
        }
        finally
        {
            Stop();
            _accepted.TrySetResult(null);
        }
    }

    private async Task HandshakeAsync(TcpClient client, CancellationToken ct)
    {
        bool kept = false;
        try
        {
            client.NoDelay = true;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(HandshakeTimeout);
            var (channel, _) = await Vms1Channel.AcceptAsPcAsync(client.GetStream(), _pcId,
                preface => preface.Source == Vms1.SourceScreen && preface.Sid.AsSpan().SequenceEqual(_sid) ? _session : null,
                timeout.Token).ConfigureAwait(false);
            var connection = new Vms1Connection(client, channel);
            if (_accepted.TrySetResult(connection))
            {
                kept = true;
                VeyroTrace.Info("Media socket authenticated");
                Stop();
            }
            else
            {
                connection.Dispose();
            }
        }
        catch (Exception error) when (error is OperationCanceledException or IOException or InvalidDataException or SocketException or ObjectDisposedException)
        {
            // A wrong session, other keys or a slow peer: closed without an answer (7.5.2).
            VeyroTrace.Warn($"Media socket refused ({error.GetType().Name})");
        }
        finally
        {
            if (!kept) client.Dispose();
            _slots.Release();
        }
    }

    private void Stop()
    {
        try { _listener.Stop(); } catch (SocketException) { }
    }

    private int _taken;

    /// <summary>
    /// Waits for the authenticated connection and takes ownership of it (the caller disposes it).
    /// Null after the wait timed out or the listener was disposed.
    /// </summary>
    public async Task<Vms1Connection?> TakeAsync()
    {
        Vms1Connection? connection = await _accepted.Task.ConfigureAwait(false);
        return connection is not null && Interlocked.Exchange(ref _taken, 1) == 0 ? connection : null;
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        Stop();
        _accepted.TrySetResult(null);
        // A connection that authenticated but was never taken is closed here.
        if (await _accepted.Task.ConfigureAwait(false) is { } unused && Interlocked.Exchange(ref _taken, 1) == 0) unused.Dispose();
    }
}
