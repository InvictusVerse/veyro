using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Veyro.Core.Diagnostics;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Windows.Graphics.DirectX;
using Windows.Graphics.Imaging;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;

namespace Veyro.Platform.Media;

[ComImport, Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMemoryBufferByteAccess
{
    void GetBuffer(out IntPtr buffer, out uint capacity);
}

/// <summary>
/// Decodes the phone camera's video a second time, off screen, into NV12 frames of the size an
/// app chose for the virtual camera (ADR-067). The preview window keeps its own player. Media
/// Foundation decodes (on the GPU when it can) and scales into a GPU surface; the frame is then
/// read back, turned by the camera's rotation, mirrored if asked, and centred with black bars.
/// At most <see cref="Fps"/> frames a second. Frames are user data: nothing here logs them.
/// </summary>
public sealed class FrameTap : IDisposable
{
    public const int Fps = 30;

    private readonly MediaReceiveQueue _queue = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly object _gate = new();
    private Vms1.VideoConfig? _config;
    private MediaPlayer? _player;
    private MediaStreamSource? _source;
    private Vms1.VideoConfig? _built;
    private long _basePts = -1;
    private int _busy;
    private long _lastFrame;
    private VideoFrame? _gpu;
    private VideoFrame? _cpu;
    private bool _bgra;
    private byte[] _srcY = [], _srcUV = [], _out = [];
    private volatile int _targetW = 1280, _targetH = 720;
    private volatile bool _mirror;
    private bool _disposed;

    /// <summary>An NV12 frame of the target size; the array is reused, copy it before returning. Background thread.</summary>
    public event Action<byte[], int, int>? FrameReady;

    /// <summary>The decoder needs a keyframe. Background thread.</summary>
    public event Action? KeyframeNeeded;

    public void SetTarget(int width, int height, bool mirror)
    {
        if (width is < 16 or > 3840 || height is < 16 or > 2160) return;
        _targetW = width & ~1;
        _targetH = height & ~1;
        _mirror = mirror;
    }

    public void OnConfig(Vms1.VideoConfig config)
    {
        lock (_gate) _config = config;
    }

    public void OnAccessUnit(AccessUnit unit)
    {
        Vms1.VideoConfig? config;
        lock (_gate)
        {
            if (_disposed) return;
            config = _config;
        }
        if (config is null) return;
        if (_built is null || _built.Codec != config.Codec || _built.Width != config.Width || _built.Height != config.Height)
        {
            Build(config);
            if (_queue.Resync() && !unit.Key) KeyframeNeeded?.Invoke();
        }
        else if (_built != config)
        {
            _built = config; // rotation only
        }
        if (_queue.Enqueue(unit)) KeyframeNeeded?.Invoke();
    }

    private void Build(Vms1.VideoConfig video)
    {
        Teardown();
        VideoEncodingProperties props = video.Codec == Vms1.VideoCodecH265 ? VideoEncodingProperties.CreateHevc() : VideoEncodingProperties.CreateH264();
        props.Width = (uint)video.Width;
        props.Height = (uint)video.Height;
        var source = new MediaStreamSource(new VideoStreamDescriptor(props)) { IsLive = true, CanSeek = false, BufferTime = TimeSpan.Zero };
        source.Starting += (_, args) => args.Request.SetActualStartPosition(TimeSpan.Zero);
        source.SampleRequested += (s, args) => _ = OnSampleRequestedAsync(args);
        var player = new MediaPlayer { RealTimePlayback = true, AutoPlay = true, IsMuted = true, IsVideoFrameServerEnabled = true };
        player.VideoFrameAvailable += OnVideoFrame;
        player.MediaFailed += (_, args) =>
        {
            VeyroTrace.Warn($"Virtual camera decode failed: {args.Error}");
            if (_queue.Resync()) KeyframeNeeded?.Invoke();
        };
        player.Source = MediaSource.CreateFromMediaStreamSource(source);
        _source = source;
        _player = player;
        _built = video;
        _basePts = -1;
    }

    private async Task OnSampleRequestedAsync(MediaStreamSourceSampleRequestedEventArgs args)
    {
        MediaStreamSourceSampleRequest request = args.Request;
        MediaStreamSourceSampleRequestDeferral deferral = request.GetDeferral();
        try
        {
            AccessUnit? unit = await _queue.DequeueAsync(_stop.Token).ConfigureAwait(false);
            if (unit is null) return;
            if (_basePts < 0) _basePts = unit.PtsUs;
            var sample = MediaStreamSample.CreateFromBuffer(unit.Data.AsBuffer(), TimeSpan.FromTicks(Math.Max(0, unit.PtsUs - _basePts) * 10));
            sample.KeyFrame = unit.Key;
            sample.Discontinuous = unit.Discontinuity;
            request.Sample = sample;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Virtual camera sample not delivered: {error.GetType().Name}");
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void OnVideoFrame(MediaPlayer sender, object args)
    {
        long now = Environment.TickCount64;
        if (now - _lastFrame < 1000 / Fps - 2) return;
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        _lastFrame = now;
        _ = CaptureAsync(sender).ContinueWith(_ => Interlocked.Exchange(ref _busy, 0), TaskScheduler.Default);
    }

    private async Task CaptureAsync(MediaPlayer player)
    {
        try
        {
            Vms1.VideoConfig? video = _built;
            if (video is null || _disposed) return;
            int rotation = video.Rotation;
            bool quarter = rotation is 90 or 270;
            int tw = _targetW, th = _targetH;
            // The picture after rotation, fitted into the target; then the size to scale to before turning it.
            int rw = quarter ? video.VisH : video.VisW, rh = quarter ? video.VisW : video.VisH;
            double scale = Math.Min((double)tw / rw, (double)th / rh);
            int pw = Math.Max(2, (int)(rw * scale) & ~1), ph = Math.Max(2, (int)(rh * scale) & ~1);
            int sw = quarter ? ph : pw, sh = quarter ? pw : ph;

            if (_gpu is null || _gpu.Direct3DSurface.Description.Width != sw || _gpu.Direct3DSurface.Description.Height != sh)
            {
                _gpu?.Dispose();
                _cpu?.Dispose();
                _gpu = VideoFrame.CreateAsDirect3D11SurfaceBacked(_bgra ? DirectXPixelFormat.B8G8R8A8UIntNormalized : DirectXPixelFormat.NV12, sw, sh);
                _cpu = new VideoFrame(_bgra ? BitmapPixelFormat.Bgra8 : BitmapPixelFormat.Nv12, sw, sh);
            }
            try
            {
                player.CopyFrameToVideoSurface(_gpu.Direct3DSurface);
            }
            catch (Exception) when (!_bgra)
            {
                // Some video processors cannot write NV12 surfaces: use BGRA and convert here.
                VeyroTrace.Info("Virtual camera: NV12 surfaces refused, using BGRA");
                _bgra = true;
                _gpu?.Dispose();
                _gpu = null;
                return;
            }
            await _gpu.CopyToAsync(_cpu);
            if (!ReadPlanes(_cpu!.SoftwareBitmap, sw, sh)) return;
            int bytes = tw * th * 3 / 2;
            if (_out.Length != bytes) _out = new byte[bytes];
            Nv12.Compose(_srcY, _srcUV, sw, sh, rotation, _mirror, _out, tw, th);
            FrameReady?.Invoke(_out, tw, th);
        }
        catch (Exception error) when (error is not OutOfMemoryException)
        {
            VeyroTrace.Warn($"Virtual camera frame skipped: {error.GetType().Name}");
        }
    }

    /// <summary>Copies the bitmap's planes into <see cref="_srcY"/> and <see cref="_srcUV"/> (tight, NV12).</summary>
    private bool ReadPlanes(SoftwareBitmap? bitmap, int w, int h)
    {
        if (bitmap is null) return false;
        if (_srcY.Length != w * h) _srcY = new byte[w * h];
        if (_srcUV.Length != w * h / 2) _srcUV = new byte[w * h / 2];
        using BitmapBuffer buffer = bitmap.LockBuffer(BitmapBufferAccessMode.Read);
        using var reference = buffer.CreateReference();
        WinRT.CastExtensions.As<IMemoryBufferByteAccess>(reference).GetBuffer(out IntPtr data, out _);
        if (bitmap.BitmapPixelFormat == BitmapPixelFormat.Nv12)
        {
            BitmapPlaneDescription y = buffer.GetPlaneDescription(0), uv = buffer.GetPlaneDescription(1);
            for (int row = 0; row < h; row++) Marshal.Copy(data + y.StartIndex + row * y.Stride, _srcY, row * w, w);
            for (int row = 0; row < h / 2; row++) Marshal.Copy(data + uv.StartIndex + row * uv.Stride, _srcUV, row * w, w);
            return true;
        }
        BitmapPlaneDescription p = buffer.GetPlaneDescription(0);
        byte[] bgra = new byte[w * 4];
        for (int row = 0; row < h; row++)
        {
            Marshal.Copy(data + p.StartIndex + row * p.Stride, bgra, 0, w * 4);
            Nv12.FromBgraRow(bgra, row, w, _srcY, _srcUV);
        }
        return true;
    }

    private void Teardown()
    {
        if (_player is { } player)
        {
            player.VideoFrameAvailable -= OnVideoFrame;
            player.Dispose();
        }
        _player = null;
        _source = null;
        _built = null;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
        }
        _stop.Cancel();
        _queue.Close();
        Teardown();
        _gpu?.Dispose();
        _cpu?.Dispose();
    }
}
