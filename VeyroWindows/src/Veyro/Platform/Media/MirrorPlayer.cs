using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Channels;
using Microsoft.UI.Xaml.Controls;
using Veyro.Core.Diagnostics;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;

namespace Veyro.Platform.Media;

/// <summary>
/// Plays a mirrored phone screen (ADR-066) through Media Foundation: a live
/// <see cref="MediaStreamSource"/> fed with the phone's H.264/H.265 access units and PCM sound,
/// rendered by a <see cref="MediaPlayerElement"/>. Media Foundation decodes on the GPU (DXVA)
/// whenever the GPU has the codec and falls back to software by itself, and keeps video in step
/// with the sound: both carry the phone's capture clock, and the sound is the master clock.
///
/// The player is built at the first access unit (both configs have arrived by then) and rebuilt
/// when the codec, the size or the presence of sound changes; a rebuild waits for a keyframe.
/// Frames and sound are user data: nothing here logs them.
/// </summary>
public sealed class MirrorPlayer : IDisposable
{
    /// <summary>How long a sound request waits before it is answered with silence (keeps the clock running).</summary>
    private static readonly TimeSpan AudioWait = TimeSpan.FromMilliseconds(100);
    private static readonly TimeSpan SilenceLength = TimeSpan.FromMilliseconds(20);

    private readonly MediaPlayerElement _element;
    private readonly DispatcherQueue _ui;
    private readonly MediaReceiveQueue _video;
    private readonly Channel<(long PtsUs, byte[] Samples)> _audio =
        Channel.CreateBounded<(long, byte[])>(new BoundedChannelOptions(20) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
    private readonly CancellationTokenSource _stop = new();
    private readonly object _gate = new();

    private Vms1.VideoConfig? _videoConfig;
    private Vms1.AudioConfig? _audioConfig;
    private Built? _built;
    private bool _disposed;

    private sealed class Built(MediaStreamSource source, MediaPlayer player, Vms1.VideoConfig video, Vms1.AudioConfig? audio, CancellationToken stop)
    {
        public MediaStreamSource Source { get; } = source;
        public MediaPlayer Player { get; } = player;
        public Vms1.VideoConfig Video { get; } = video;
        public Vms1.AudioConfig? Audio { get; } = audio;
        /// <summary>Cancelled when this player is replaced, so its waiting requests never take the next player's frames.</summary>
        public CancellationTokenSource Retired { get; } = CancellationTokenSource.CreateLinkedTokenSource(stop);
        public long BasePtsUs = -1;
        public TimeSpan NextAudio = TimeSpan.Zero;
    }

    /// <param name="ui">The element's UI thread; the player is attached and detached there.</param>
    public MirrorPlayer(MediaPlayerElement element, DispatcherQueue ui, MediaReceiveQueue video)
    {
        _element = element;
        _ui = ui;
        _video = video;
    }

    /// <summary>The player needs a keyframe (it was rebuilt, or the decoder failed). Raised on a background thread.</summary>
    public event Action? KeyframeNeeded;

    /// <summary>The first picture of a player was decoded. Raised on a background thread.</summary>
    public event Action? Playing;

    public void ConfigureVideo(Vms1.VideoConfig config)
    {
        lock (_gate) _videoConfig = config;
    }

    public void ConfigureAudio(Vms1.AudioConfig config)
    {
        lock (_gate) _audioConfig = config;
    }

    /// <summary>
    /// Call for each access unit before it is queued; builds or rebuilds the player when the
    /// configs changed. A rebuild drops what the queue still holds for the old decoder, so the
    /// new one starts at <paramref name="key"/> or asks for a keyframe.
    /// </summary>
    public void BeforeAccessUnit(bool key)
    {
        Vms1.VideoConfig? video;
        Vms1.AudioConfig? audio;
        Built? current;
        lock (_gate)
        {
            if (_disposed) return;
            video = _videoConfig;
            audio = _audioConfig;
            current = _built;
        }
        if (video is null) return;
        bool sameVideo = current is not null && current.Video.Codec == video.Codec && current.Video.Width == video.Width && current.Video.Height == video.Height;
        bool sameAudio = current is not null && Equals(current.Audio, audio);
        if (sameVideo && sameAudio)
        {
            if (current!.Video != video) ApplyGeometry(current.Player, video);
            return;
        }
        if (!Rebuild(video, audio)) return;
        bool askKey = current is not null && _video.Resync();
        if (askKey && !key) KeyframeNeeded?.Invoke();
    }

    /// <summary>Phone sound, whole s16le sample frames, after <see cref="ConfigureAudio"/>.</summary>
    public void PushAudio(long ptsUs, byte[] samples) => _audio.Writer.TryWrite((ptsUs, samples));

    private bool Rebuild(Vms1.VideoConfig video, Vms1.AudioConfig? audio)
    {
        VideoEncodingProperties videoProps = video.Codec == Vms1.VideoCodecH265 ? VideoEncodingProperties.CreateHevc() : VideoEncodingProperties.CreateH264();
        videoProps.Width = (uint)video.Width;
        videoProps.Height = (uint)video.Height;
        var videoStream = new VideoStreamDescriptor(videoProps);
        MediaStreamSource source;
        if (audio is not null)
        {
            var audioStream = new AudioStreamDescriptor(AudioEncodingProperties.CreatePcm((uint)audio.Rate, (uint)audio.Channels, 16));
            source = new MediaStreamSource(videoStream, audioStream);
        }
        else
        {
            source = new MediaStreamSource(videoStream);
        }
        source.IsLive = true;
        source.CanSeek = false;
        source.BufferTime = TimeSpan.Zero;
        var player = new MediaPlayer
        {
            RealTimePlayback = true,
            AutoPlay = true,
            IsLoopingEnabled = false,
        };
        var built = new Built(source, player, video, audio, _stop.Token);
        source.Starting += (_, args) => args.Request.SetActualStartPosition(TimeSpan.Zero);
        source.SampleRequested += (s, args) => _ = OnSampleRequestedAsync(built, args);
        player.MediaFailed += (_, args) =>
        {
            VeyroTrace.Warn($"Mirror playback failed: {args.Error}");
            if (_video.Resync()) KeyframeNeeded?.Invoke();
        };
        player.PlaybackSession.PlaybackStateChanged += (session, _) =>
        {
            if (session.PlaybackState == MediaPlaybackState.Playing) Playing?.Invoke();
        };
        player.Source = MediaSource.CreateFromMediaStreamSource(source);
        ApplyGeometry(player, video);

        Built? old;
        lock (_gate)
        {
            if (_disposed)
            {
                player.Dispose();
                return false;
            }
            old = _built;
            _built = built;
        }
        old?.Retired.Cancel();
        _ui.TryEnqueue(() =>
        {
            try { _element.SetMediaPlayer(player); }
            catch (Exception error) { VeyroTrace.Error("Attaching the mirror player failed", error); }
            old?.Player.Dispose();
        });
        VeyroTrace.Info($"Mirror player built: {(video.Codec == Vms1.VideoCodecH265 ? "H.265" : "H.264")} {video.Width}x{video.Height}{(audio is null ? "" : " with sound")}");
        return true;
    }

    /// <summary>Rotation and the visible part of the coded frame (Android 12L+ letterboxes inside it).</summary>
    private static void ApplyGeometry(MediaPlayer player, Vms1.VideoConfig video)
    {
        try
        {
            player.PlaybackSession.PlaybackRotation = video.Rotation switch
            {
                90 => MediaRotation.Clockwise90Degrees,
                180 => MediaRotation.Clockwise180Degrees,
                270 => MediaRotation.Clockwise270Degrees,
                _ => MediaRotation.None,
            };
            player.PlaybackSession.NormalizedSourceRect = new Windows.Foundation.Rect(
                (double)video.VisX / video.Width, (double)video.VisY / video.Height,
                (double)video.VisW / video.Width, (double)video.VisH / video.Height);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Mirror geometry not applied: {error.GetType().Name}");
        }
    }

    private async Task OnSampleRequestedAsync(Built built, MediaStreamSourceSampleRequestedEventArgs args)
    {
        MediaStreamSourceSampleRequest request = args.Request;
        MediaStreamSourceSampleRequestDeferral deferral = request.GetDeferral();
        try
        {
            CancellationToken ct = built.Retired.Token;
            if (request.StreamDescriptor is VideoStreamDescriptor)
            {
                AccessUnit? unit = await _video.DequeueAsync(ct).ConfigureAwait(false);
                if (unit is null || !IsCurrent(built)) return;
                if (built.BasePtsUs < 0) built.BasePtsUs = unit.PtsUs;
                var sample = MediaStreamSample.CreateFromBuffer(unit.Data.AsBuffer(), Timestamp(built, unit.PtsUs));
                sample.KeyFrame = unit.Key;
                sample.Discontinuous = unit.Discontinuity;
                request.Sample = sample;
            }
            else if (built.Audio is { } audio)
            {
                request.Sample = await NextAudioAsync(built, audio, ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Mirror sample not delivered: {error.GetType().Name}");
        }
        finally
        {
            deferral.Complete();
        }
    }

    private async Task<MediaStreamSample?> NextAudioAsync(Built built, Vms1.AudioConfig audio, CancellationToken ct)
    {
        using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
        wait.CancelAfter(AudioWait);
        try
        {
            while (true)
            {
                (long pts, byte[] samples) = await _audio.Reader.ReadAsync(wait.Token).ConfigureAwait(false);
                if (built.BasePtsUs < 0 || pts < built.BasePtsUs) continue; // before the first picture
                TimeSpan at = Timestamp(built, pts);
                // Silence was played for this moment already: skip what it stood in for.
                if (at + TimeSpan.FromMilliseconds(10) < built.NextAudio) continue;
                built.NextAudio = at + TimeSpan.FromTicks(samples.Length / audio.FrameBytes * TimeSpan.TicksPerSecond / audio.Rate);
                return MediaStreamSample.CreateFromBuffer(samples.AsBuffer(), at);
            }
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            // Nothing arrived in time: a little silence keeps the audio clock, and so the video, moving.
            int frames = (int)(audio.Rate * SilenceLength.TotalSeconds);
            var silence = MediaStreamSample.CreateFromBuffer(new byte[frames * audio.FrameBytes].AsBuffer(), built.NextAudio);
            built.NextAudio += SilenceLength;
            return silence;
        }
    }

    private static TimeSpan Timestamp(Built built, long ptsUs) => TimeSpan.FromTicks(Math.Max(0, ptsUs - built.BasePtsUs) * 10);

    private bool IsCurrent(Built built)
    {
        lock (_gate) return ReferenceEquals(_built, built);
    }

    public void Dispose()
    {
        Built? built;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            built = _built;
            _built = null;
        }
        _stop.Cancel();
        _audio.Writer.TryComplete();
        if (built is null) return;
        _ui.TryEnqueue(() =>
        {
            try { _element.SetMediaPlayer(null); } catch (Exception error) { VeyroTrace.Warn($"Detaching the mirror player failed: {error.GetType().Name}"); }
            built.Player.Dispose();
        });
    }
}
