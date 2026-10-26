using System.Runtime.InteropServices;

namespace Veyro.Platforms.Windows.Interop;

/// <summary>
/// Minimal Core Audio interop for the default render endpoint's master volume and mute
/// (mmdeviceapi.h / endpointvolume.h). Only the vtable slots up to the last method used are
/// declared; their order must match the native interfaces exactly.
/// </summary>
internal static class CoreAudioInterop
{
    private const int EDataFlowRender = 0;
    private const int ERoleMultimedia = 1;
    private const int ClsctxAll = 0x17;
    private static readonly Guid AudioEndpointVolumeIid = new("5CDF2C82-841E-4546-9722-0CF74078229A");

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out nint devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, int clsCtx, nint activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport]
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(nint notify);
        [PreserveSig] int UnregisterControlChangeNotify(nint notify);
        [PreserveSig] int GetChannelCount(out uint count);
        [PreserveSig] int SetMasterVolumeLevel(float levelDb, ref Guid eventContext);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid eventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid eventContext);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid eventContext);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid eventContext);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    /// <summary>
    /// Master volume of the current default output device. The endpoint is resolved per call so
    /// a change of default device (headphones plugged in) is followed without notifications.
    /// </summary>
    internal sealed class MasterVolume
    {
        /// <summary>Volume 0..100 and mute, or null when there is no output device.</summary>
        public (int Volume, bool Muted)? Read()
        {
            IAudioEndpointVolume? endpoint = Open();
            if (endpoint is null) return null;
            try
            {
                if (endpoint.GetMasterVolumeLevelScalar(out float level) < 0 || endpoint.GetMute(out bool muted) < 0)
                    return null;
                return ((int)Math.Round(Math.Clamp(level, 0f, 1f) * 100f), muted);
            }
            finally
            {
                Marshal.FinalReleaseComObject(endpoint);
            }
        }

        public void SetVolume(int percent)
        {
            IAudioEndpointVolume endpoint = Open() ?? throw new InvalidOperationException("No audio output device.");
            try
            {
                Guid context = Guid.Empty;
                Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar(Math.Clamp(percent, 0, 100) / 100f, ref context));
            }
            finally
            {
                Marshal.FinalReleaseComObject(endpoint);
            }
        }

        public void SetMute(bool muted)
        {
            IAudioEndpointVolume endpoint = Open() ?? throw new InvalidOperationException("No audio output device.");
            try
            {
                Guid context = Guid.Empty;
                Marshal.ThrowExceptionForHR(endpoint.SetMute(muted, ref context));
            }
            finally
            {
                Marshal.FinalReleaseComObject(endpoint);
            }
        }

        private static IAudioEndpointVolume? Open()
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDevice? device = null;
            try
            {
                // E_NOTFOUND when no output device exists: not an error worth throwing.
                if (enumerator.GetDefaultAudioEndpoint(EDataFlowRender, ERoleMultimedia, out device) < 0 || device is null)
                    return null;
                Guid iid = AudioEndpointVolumeIid;
                if (device.Activate(ref iid, ClsctxAll, 0, out object instance) < 0) return null;
                return (IAudioEndpointVolume)instance;
            }
            finally
            {
                if (device is not null) Marshal.FinalReleaseComObject(device);
                Marshal.FinalReleaseComObject(enumerator);
            }
        }
    }
}
