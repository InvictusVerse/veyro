using System.Runtime.InteropServices;
using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Media;

/// <summary>
/// What this PC plays, captured with WASAPI loopback on the default output device (ADR-073), as
/// 16-bit stereo PCM at the device's rate in about 10 ms packets. Float and 16-bit mix formats are
/// converted; more than two channels keep the first two. Silence comes as zero samples, so the
/// phone's clock keeps running. Sound is user data: nothing here logs it.
/// </summary>
public sealed class LoopbackCapture : IDisposable
{
    private const int ClsctxAll = 0x17;
    private const int ShareModeShared = 0;
    private const int StreamFlagsLoopback = 0x00020000;
    private const int BufferFlagsSilent = 0x2;
    private const long BufferDuration = 2_000_000; // 200 ms in 100 ns units
    private const ushort FormatPcm = 1, FormatFloat = 3, FormatExtensible = 0xFFFE;
    private static readonly Guid SubtypeFloat = new("00000003-0000-0010-8000-00AA00389B71");
    private static readonly Guid AudioClientIid = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid CaptureClientIid = new("C8ADBD64-E71E-48a0-A4DE-185C395CD317");

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out nint devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, nint activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, nint format, nint sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint frames);
        [PreserveSig] int IsFormatSupported(int shareMode, nint format, out nint closest);
        [PreserveSig] int GetMixFormat(out nint format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(nint handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out nint data, out uint frames, out int flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    private readonly CancellationTokenSource _stop = new();
    private Thread? _thread;

    /// <summary>The device's sample rate once started.</summary>
    public int Rate { get; private set; }

    /// <summary>16-bit interleaved stereo; the array is new each call. Capture thread.</summary>
    public event Action<byte[]>? Packet;

    /// <summary>The capture ended by itself (device removed, error). Capture thread.</summary>
    public event Action? Ended;

    /// <summary>Opens the default output device. False when there is none or Windows refuses.</summary>
    public bool Start()
    {
        var ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _thread = new Thread(() => Run(ready)) { IsBackground = true, Name = "VeyroLoopback", Priority = ThreadPriority.AboveNormal };
        _thread.SetApartmentState(ApartmentState.MTA);
        _thread.Start();
        return ready.Task.Wait(TimeSpan.FromSeconds(5)) && ready.Task.Result;
    }

    private void Run(TaskCompletionSource<bool> ready)
    {
        IAudioClient? client = null;
        IAudioCaptureClient? capture = null;
        nint format = 0;
        bool started = false;
        try
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            if (enumerator.GetDefaultAudioEndpoint(0 /* render */, 1 /* multimedia */, out IMMDevice device) < 0) { ready.TrySetResult(false); return; }
            Guid iid = AudioClientIid;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, ClsctxAll, 0, out object raw));
            client = (IAudioClient)raw;
            Marshal.ThrowExceptionForHR(client.GetMixFormat(out format));
            ushort tag = (ushort)Marshal.ReadInt16(format, 0);
            int channels = Marshal.ReadInt16(format, 2);
            int rate = Marshal.ReadInt32(format, 4);
            int bits = Marshal.ReadInt16(format, 14);
            bool isFloat = tag == FormatFloat ||
                (tag == FormatExtensible && Marshal.PtrToStructure<Guid>(format + 24) == SubtypeFloat);
            if (!(isFloat && bits == 32) && !(!isFloat && bits == 16) || channels < 1)
            {
                VeyroTrace.Warn($"PC sound: mix format not supported ({bits}-bit, tag {tag})");
                ready.TrySetResult(false);
                return;
            }
            Marshal.ThrowExceptionForHR(client.Initialize(ShareModeShared, StreamFlagsLoopback, BufferDuration, 0, format, 0));
            Guid captureIid = CaptureClientIid;
            Marshal.ThrowExceptionForHR(client.GetService(ref captureIid, out object rawCapture));
            capture = (IAudioCaptureClient)rawCapture;
            Marshal.ThrowExceptionForHR(client.Start());
            started = true;
            Rate = rate;
            ready.TrySetResult(true);
            VeyroTrace.Info($"PC sound capture started: {rate} Hz, {channels} channels");

            int packetFrames = rate / 100;
            var pending = new List<short>(packetFrames * 4);
            long silentSince = Environment.TickCount64;
            while (!_stop.IsCancellationRequested)
            {
                Thread.Sleep(5);
                int got = 0;
                while (capture.GetNextPacketSize(out uint next) >= 0 && next > 0)
                {
                    Marshal.ThrowExceptionForHR(capture.GetBuffer(out nint data, out uint frames, out int flags, out _, out _));
                    got += (int)frames;
                    for (int i = 0; i < frames; i++)
                    {
                        for (int c = 0; c < 2; c++)
                        {
                            int source = Math.Min(c, channels - 1);
                            short sample = 0;
                            if ((flags & BufferFlagsSilent) == 0)
                            {
                                if (isFloat)
                                {
                                    float f = BitConverter.Int32BitsToSingle(Marshal.ReadInt32(data, (i * channels + source) * 4));
                                    sample = (short)Math.Clamp((int)(f * 32767f), short.MinValue, short.MaxValue);
                                }
                                else
                                {
                                    sample = Marshal.ReadInt16(data, (i * channels + source) * 2);
                                }
                            }
                            pending.Add(sample);
                        }
                    }
                    capture.ReleaseBuffer(frames);
                }
                if (got > 0) silentSince = Environment.TickCount64;
                else if (Environment.TickCount64 - silentSince >= 10)
                {
                    // Windows sends nothing while nothing plays: send silence so the phone keeps time.
                    long missing = (Environment.TickCount64 - silentSince) * rate / 1000;
                    for (long i = 0; i < missing * 2; i++) pending.Add(0);
                    silentSince = Environment.TickCount64;
                }
                while (pending.Count >= packetFrames * 2)
                {
                    byte[] packet = new byte[packetFrames * 4];
                    for (int i = 0; i < packetFrames * 2; i++)
                    {
                        short s = pending[i];
                        packet[2 * i] = (byte)s;
                        packet[2 * i + 1] = (byte)(s >> 8);
                    }
                    pending.RemoveRange(0, packetFrames * 2);
                    Packet?.Invoke(packet);
                }
            }
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"PC sound capture failed: {error.GetType().Name}");
            ready.TrySetResult(false);
            if (started && !_stop.IsCancellationRequested) Ended?.Invoke();
        }
        finally
        {
            if (started) client?.Stop();
            if (format != 0) Marshal.FreeCoTaskMem(format);
            if (capture is not null) Marshal.FinalReleaseComObject(capture);
            if (client is not null) Marshal.FinalReleaseComObject(client);
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _thread?.Join(1000);
    }
}
