// Veyro Phone Camera: a Windows 11 virtual camera (ADR-067).
//
// A Media Foundation media source that Windows' Frame Server loads for the camera registered with
// MFCreateVirtualCamera. It shows the frames Veyro writes into a shared-memory section (VVC1),
// already decoded, scaled and in NV12 at the size the app chose. Without frames it shows black.
// The section is created here, in Frame Server, so it can live in the Global namespace; its DACL
// lets only SYSTEM, LOCAL SERVICE and the Windows user who set the camera up (UserSid, written at
// setup) open it. The source also tells Veyro that an app is using the camera (demandTick), so
// Veyro can start the phone's camera only then.
//
// Structure follows Microsoft's VirtualCamera sample (Windows-Camera, SimpleMediaSource): an
// IMFActivate, an IMFMediaSourceEx with IKsControl, and one IMFMediaStream2.

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <ks.h>
#include <ksproxy.h>
#include <ksmedia.h>
#include <sddl.h>
#include <wrl.h>
#include <wrl/module.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using namespace Microsoft::WRL;

// {8A4F7E21-3C6B-4D59-9E1A-5B2C7D3F6A10}
static const wchar_t* kSourceId = L"{8A4F7E21-3C6B-4D59-9E1A-5B2C7D3F6A10}";
static const wchar_t* kFriendlyName = L"Veyro Phone Camera";
static const wchar_t* kSectionGlobal = L"Global\\VeyroVirtualCamera_v1";
static const wchar_t* kSectionLocal = L"Local\\VeyroVirtualCamera_v1";
static const wchar_t* kSettingsKey = L"SOFTWARE\\Veyro\\VirtualCamera";

// PINNAME_VIDEO_CAPTURE
static const GUID kPinVideoCapture = { 0xfb6c4281, 0x0353, 0x11d1, { 0x90, 0x5f, 0x00, 0x00, 0xc0, 0xcc, 0x16, 0xba } };

// ── VVC1 shared memory (little-endian) ──────────────────────────────────────────────────────────
// 0 magic 'VVC1' | 4 version 1 | 8 header size | 12 max frame bytes
// 16 u64 demandTick (source: GetTickCount64 while an app reads frames)
// 24 u32 wantW | 28 u32 wantH (source: the size the app chose)
// 32 u32 seq (Veyro: odd while writing) | 36 frameW | 40 frameH | 44 frameBytes
// 48 u64 frameTick (Veyro: GetTickCount64 of the frame) | 4096 frame data (NV12)
static const UINT32 kMagic = 0x31435656;
static const UINT32 kVersion = 1;
static const UINT32 kHeaderSize = 4096;
static const UINT32 kMaxW = 3840, kMaxH = 2160;
static const UINT32 kMaxFrameBytes = kMaxW * kMaxH * 3 / 2;
static const UINT32 kSectionSize = kHeaderSize + kMaxFrameBytes;
static const ULONGLONG kStaleMs = 2000;

struct SizeOption { UINT32 w, h; };
static const SizeOption kSizes[] = { { 1280, 720 }, { 1920, 1080 }, { 640, 360 }, { 3840, 2160 } };
static const UINT32 kFps = 30;

static std::atomic<long> g_objects{ 0 };

static std::wstring ReadUserSid()
{
    wchar_t buffer[256] = {};
    DWORD size = sizeof(buffer);
    if (RegGetValueW(HKEY_LOCAL_MACHINE, kSettingsKey, L"UserSid", RRF_RT_REG_SZ, nullptr, buffer, &size) != ERROR_SUCCESS) return L"";
    std::wstring sid(buffer);
    // An SID has only these characters; anything else is not used in the SDDL below.
    for (wchar_t c : sid) if (!(c == L'S' || c == L'-' || (c >= L'0' && c <= L'9'))) return L"";
    return sid;
}

/// The section: SYSTEM and LOCAL SERVICE full access, the set-up user read/write. Global first.
class SharedFrames
{
public:
    ~SharedFrames() { Close(); }

    bool Open()
    {
        if (m_view) return true;
        std::wstring sid = ReadUserSid();
        std::wstring sddl = L"D:P(A;;GA;;;SY)(A;;GA;;;LS)";
        // Without a set-up user (older setup), interactive users: still no other session's services.
        sddl += sid.empty() ? L"(A;;GRGW;;;IU)" : L"(A;;GRGW;;;" + sid + L")";
        PSECURITY_DESCRIPTOR sd = nullptr;
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr)) return false;
        SECURITY_ATTRIBUTES sa = { sizeof(sa), sd, FALSE };
        m_mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, &sa, PAGE_READWRITE, 0, kSectionSize, kSectionGlobal);
        if (!m_mapping) m_mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, &sa, PAGE_READWRITE, 0, kSectionSize, kSectionLocal);
        LocalFree(sd);
        if (!m_mapping) return false;
        m_view = static_cast<BYTE*>(MapViewOfFile(m_mapping, FILE_MAP_ALL_ACCESS, 0, 0, kSectionSize));
        if (!m_view) { CloseHandle(m_mapping); m_mapping = nullptr; return false; }
        UINT32* h = reinterpret_cast<UINT32*>(m_view);
        h[0] = kMagic; h[1] = kVersion; h[2] = kHeaderSize; h[3] = kMaxFrameBytes;
        return true;
    }

    void Close()
    {
        if (m_view) { UnmapViewOfFile(m_view); m_view = nullptr; }
        if (m_mapping) { CloseHandle(m_mapping); m_mapping = nullptr; }
    }

    void SetWanted(UINT32 w, UINT32 h)
    {
        if (!m_view) return;
        reinterpret_cast<volatile UINT32*>(m_view + 24)[0] = w;
        reinterpret_cast<volatile UINT32*>(m_view + 28)[0] = h;
    }

    void Beat()
    {
        if (m_view) InterlockedExchange64(reinterpret_cast<volatile LONG64*>(m_view + 16), static_cast<LONG64>(GetTickCount64()));
    }

    /// Copies a fresh, complete frame of w x h into dst; false when there is none.
    bool Read(BYTE* dst, UINT32 w, UINT32 h)
    {
        if (!m_view) return false;
        volatile UINT32* seqp = reinterpret_cast<volatile UINT32*>(m_view + 32);
        UINT32 bytes = w * h * 3 / 2;
        for (int attempt = 0; attempt < 3; attempt++)
        {
            UINT32 before = *seqp;
            MemoryBarrier();
            if (before & 1) { Sleep(1); continue; }
            UINT32 fw = *reinterpret_cast<volatile UINT32*>(m_view + 36);
            UINT32 fh = *reinterpret_cast<volatile UINT32*>(m_view + 40);
            UINT32 fb = *reinterpret_cast<volatile UINT32*>(m_view + 44);
            ULONGLONG tick = static_cast<ULONGLONG>(*reinterpret_cast<volatile LONG64*>(m_view + 48));
            if (fw != w || fh != h || fb != bytes || fb > kMaxFrameBytes) return false;
            if (GetTickCount64() - tick > kStaleMs) return false;
            memcpy(dst, m_view + kHeaderSize, bytes);
            MemoryBarrier();
            if (*seqp == before) return true;
        }
        return false;
    }

private:
    HANDLE m_mapping = nullptr;
    BYTE* m_view = nullptr;
};

// ── The stream ──────────────────────────────────────────────────────────────────────────────────

class VeyroStream;

class VeyroSource : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaSourceEx, IMFMediaSource, IMFMediaEventGenerator>, IMFGetService, IKsControl>
{
public:
    HRESULT RuntimeClassInitialize();

    // IMFMediaEventGenerator
    IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override;
    IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID ext, HRESULT status, const PROPVARIANT* pv) override;
    // IMFMediaSource
    IFACEMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** pd) override;
    IFACEMETHODIMP GetCharacteristics(DWORD* characteristics) override;
    IFACEMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    IFACEMETHODIMP Shutdown() override;
    IFACEMETHODIMP Start(IMFPresentationDescriptor* pd, const GUID* timeFormat, const PROPVARIANT* start) override;
    IFACEMETHODIMP Stop() override;
    // IMFMediaSourceEx
    IFACEMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override;
    IFACEMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes** attributes) override;
    IFACEMETHODIMP SetD3DManager(IUnknown*) override { return S_OK; }
    // IMFGetService
    IFACEMETHODIMP GetService(REFGUID, REFIID, LPVOID*) override { return MF_E_UNSUPPORTED_SERVICE; }
    // IKsControl: no camera controls.
    IFACEMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    IFACEMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    IFACEMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

    VeyroSource() { g_objects++; }
    ~VeyroSource() { g_objects--; }

private:
    std::mutex m_lock;
    ComPtr<IMFMediaEventQueue> m_events;
    ComPtr<IMFPresentationDescriptor> m_pd;
    ComPtr<IMFAttributes> m_attributes;
    ComPtr<VeyroStream> m_stream;
    bool m_shutdown = false;
    bool m_started = false;
};

class VeyroStream : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaStream2, IMFMediaStream, IMFMediaEventGenerator>, IKsControl>
{
public:
    HRESULT RuntimeClassInitialize(VeyroSource* source);

    IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override
    {
        std::lock_guard<std::mutex> g(m_lock);
        return m_shutdown ? MF_E_SHUTDOWN : m_events->BeginGetEvent(cb, state);
    }
    IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override
    {
        std::lock_guard<std::mutex> g(m_lock);
        return m_shutdown ? MF_E_SHUTDOWN : m_events->EndGetEvent(result, event);
    }
    IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override
    {
        ComPtr<IMFMediaEventQueue> queue;
        {
            std::lock_guard<std::mutex> g(m_lock);
            if (m_shutdown) return MF_E_SHUTDOWN;
            queue = m_events;
        }
        return queue->GetEvent(flags, event);
    }
    IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID ext, HRESULT status, const PROPVARIANT* pv) override
    {
        std::lock_guard<std::mutex> g(m_lock);
        return m_shutdown ? MF_E_SHUTDOWN : m_events->QueueEventParamVar(type, ext, status, pv);
    }
    IFACEMETHODIMP GetMediaSource(IMFMediaSource** source) override
    {
        if (!source) return E_POINTER;
        std::lock_guard<std::mutex> g(m_lock);
        if (m_shutdown) return MF_E_SHUTDOWN;
        return m_source.CopyTo(source);
    }
    IFACEMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** sd) override
    {
        if (!sd) return E_POINTER;
        std::lock_guard<std::mutex> g(m_lock);
        if (m_shutdown) return MF_E_SHUTDOWN;
        return m_sd.CopyTo(sd);
    }
    IFACEMETHODIMP RequestSample(IUnknown* token) override;
    IFACEMETHODIMP SetStreamState(MF_STREAM_STATE state) override
    {
        std::lock_guard<std::mutex> g(m_lock);
        if (m_shutdown) return MF_E_SHUTDOWN;
        m_state = state;
        return S_OK;
    }
    IFACEMETHODIMP GetStreamState(MF_STREAM_STATE* state) override
    {
        if (!state) return E_POINTER;
        std::lock_guard<std::mutex> g(m_lock);
        *state = m_state;
        return S_OK;
    }
    IFACEMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    IFACEMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    IFACEMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

    HRESULT Start();
    HRESULT Stop();
    void Shutdown();
    IMFStreamDescriptor* Descriptor() { return m_sd.Get(); }
    HRESULT GetAttributes(IMFAttributes** attributes) { return m_attributes.CopyTo(attributes); }

    VeyroStream() { g_objects++; }
    ~VeyroStream() { g_objects--; }

private:
    std::mutex m_lock;
    ComPtr<IMFMediaEventQueue> m_events;
    ComPtr<IMFMediaSource> m_source;
    ComPtr<IMFStreamDescriptor> m_sd;
    ComPtr<IMFAttributes> m_attributes;
    MF_STREAM_STATE m_state = MF_STREAM_STATE_STOPPED;
    bool m_shutdown = false;
    UINT32 m_w = 1280, m_h = 720;
    std::vector<BYTE> m_frame;
    LONGLONG m_lastTime = 0;
    SharedFrames m_shared;
};

static HRESULT CreateType(UINT32 w, UINT32 h, IMFMediaType** type)
{
    ComPtr<IMFMediaType> t;
    HRESULT hr = MFCreateMediaType(&t);
    if (SUCCEEDED(hr)) hr = t->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    if (SUCCEEDED(hr)) hr = t->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    if (SUCCEEDED(hr)) hr = MFSetAttributeSize(t.Get(), MF_MT_FRAME_SIZE, w, h);
    if (SUCCEEDED(hr)) hr = MFSetAttributeRatio(t.Get(), MF_MT_FRAME_RATE, kFps, 1);
    if (SUCCEEDED(hr)) hr = MFSetAttributeRatio(t.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    if (SUCCEEDED(hr)) hr = t->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    if (SUCCEEDED(hr)) hr = t->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
    if (SUCCEEDED(hr)) hr = t->SetUINT32(MF_MT_DEFAULT_STRIDE, w);
    if (SUCCEEDED(hr)) hr = t->SetUINT32(MF_MT_SAMPLE_SIZE, w * h * 3 / 2);
    if (SUCCEEDED(hr)) hr = t->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE);
    if (SUCCEEDED(hr)) *type = t.Detach();
    return hr;
}

HRESULT VeyroStream::RuntimeClassInitialize(VeyroSource* source)
{
    m_source = source;
    HRESULT hr = MFCreateEventQueue(&m_events);
    if (FAILED(hr)) return hr;
    const DWORD count = ARRAYSIZE(kSizes);
    ComPtr<IMFMediaType> types[count];
    IMFMediaType* raw[count];
    for (DWORD i = 0; i < count; i++)
    {
        hr = CreateType(kSizes[i].w, kSizes[i].h, &types[i]);
        if (FAILED(hr)) return hr;
        raw[i] = types[i].Get();
    }
    hr = MFCreateStreamDescriptor(0, count, raw, &m_sd);
    if (FAILED(hr)) return hr;
    ComPtr<IMFMediaTypeHandler> handler;
    hr = m_sd->GetMediaTypeHandler(&handler);
    if (SUCCEEDED(hr)) hr = handler->SetCurrentMediaType(raw[0]);
    if (FAILED(hr)) return hr;
    hr = MFCreateAttributes(&m_attributes, 8);
    if (SUCCEEDED(hr)) hr = m_attributes->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, kPinVideoCapture);
    if (SUCCEEDED(hr)) hr = m_attributes->SetUINT32(MF_DEVICESTREAM_STREAM_ID, 0);
    if (SUCCEEDED(hr)) hr = m_attributes->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1);
    if (SUCCEEDED(hr)) hr = m_attributes->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color);
    if (SUCCEEDED(hr)) hr = m_attributes->CopyAllItems(m_sd.Get());
    return hr;
}

HRESULT VeyroStream::Start()
{
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    ComPtr<IMFMediaTypeHandler> handler;
    ComPtr<IMFMediaType> type;
    HRESULT hr = m_sd->GetMediaTypeHandler(&handler);
    if (SUCCEEDED(hr)) hr = handler->GetCurrentMediaType(&type);
    if (SUCCEEDED(hr)) hr = MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, &m_w, &m_h);
    if (FAILED(hr)) return hr;
    if (m_w == 0 || m_h == 0 || m_w > kMaxW || m_h > kMaxH) return MF_E_INVALIDMEDIATYPE;
    m_frame.assign(static_cast<size_t>(m_w) * m_h * 3 / 2, 0);
    // Black in NV12: Y 16, Cb Cr 128.
    memset(m_frame.data(), 16, static_cast<size_t>(m_w) * m_h);
    memset(m_frame.data() + static_cast<size_t>(m_w) * m_h, 128, static_cast<size_t>(m_w) * m_h / 2);
    m_shared.Open();
    m_shared.SetWanted(m_w, m_h);
    m_shared.Beat();
    m_state = MF_STREAM_STATE_RUNNING;
    m_lastTime = 0;
    PROPVARIANT start;
    PropVariantInit(&start);
    start.vt = VT_I8;
    start.hVal.QuadPart = MFGetSystemTime();
    return m_events->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, &start);
}

HRESULT VeyroStream::Stop()
{
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    m_state = MF_STREAM_STATE_STOPPED;
    return m_events->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr);
}

void VeyroStream::Shutdown()
{
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return;
    m_shutdown = true;
    if (m_events) m_events->Shutdown();
    m_shared.Close();
    m_source.Reset();
}

IFACEMETHODIMP VeyroStream::RequestSample(IUnknown* token)
{
    std::unique_lock<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    if (m_state != MF_STREAM_STATE_RUNNING) return MF_E_INVALIDREQUEST;

    // At most kFps samples a second, whoever asks.
    const LONGLONG duration = 10'000'000LL / kFps;
    LONGLONG now = MFGetSystemTime();
    if (m_lastTime != 0 && now - m_lastTime < duration)
    {
        DWORD wait = static_cast<DWORD>((duration - (now - m_lastTime)) / 10'000);
        g.unlock();
        if (wait > 0) Sleep(wait);
        g.lock();
        if (m_shutdown) return MF_E_SHUTDOWN;
        now = MFGetSystemTime();
    }
    m_lastTime = now;
    m_shared.Beat();
    // A fresh frame from Veyro, else the last one, else black.
    m_shared.Read(m_frame.data(), m_w, m_h);

    DWORD bytes = static_cast<DWORD>(m_frame.size());
    ComPtr<IMFMediaBuffer> buffer;
    ComPtr<IMFSample> sample;
    HRESULT hr = MFCreateMemoryBuffer(bytes, &buffer);
    if (SUCCEEDED(hr))
    {
        BYTE* data = nullptr;
        hr = buffer->Lock(&data, nullptr, nullptr);
        if (SUCCEEDED(hr))
        {
            memcpy(data, m_frame.data(), bytes);
            buffer->Unlock();
            hr = buffer->SetCurrentLength(bytes);
        }
    }
    if (SUCCEEDED(hr)) hr = MFCreateSample(&sample);
    if (SUCCEEDED(hr)) hr = sample->AddBuffer(buffer.Get());
    if (SUCCEEDED(hr)) hr = sample->SetSampleTime(now);
    if (SUCCEEDED(hr)) hr = sample->SetSampleDuration(duration);
    if (SUCCEEDED(hr) && token) hr = sample->SetUnknown(MFSampleExtension_Token, token);
    if (SUCCEEDED(hr)) hr = m_events->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get());
    return hr;
}

// ── The source ──────────────────────────────────────────────────────────────────────────────────

HRESULT VeyroSource::RuntimeClassInitialize()
{
    HRESULT hr = MFCreateEventQueue(&m_events);
    if (SUCCEEDED(hr)) hr = MFCreateAttributes(&m_attributes, 4);
    if (SUCCEEDED(hr)) hr = MakeAndInitialize<VeyroStream>(&m_stream, this);
    if (FAILED(hr)) return hr;
    IMFStreamDescriptor* sd = m_stream->Descriptor();
    hr = MFCreatePresentationDescriptor(1, &sd, &m_pd);
    if (SUCCEEDED(hr)) hr = m_pd->SelectStream(0);
    return hr;
}

IFACEMETHODIMP VeyroSource::BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state)
{
    std::lock_guard<std::mutex> g(m_lock);
    return m_shutdown ? MF_E_SHUTDOWN : m_events->BeginGetEvent(cb, state);
}

IFACEMETHODIMP VeyroSource::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event)
{
    std::lock_guard<std::mutex> g(m_lock);
    return m_shutdown ? MF_E_SHUTDOWN : m_events->EndGetEvent(result, event);
}

IFACEMETHODIMP VeyroSource::GetEvent(DWORD flags, IMFMediaEvent** event)
{
    ComPtr<IMFMediaEventQueue> queue;
    {
        std::lock_guard<std::mutex> g(m_lock);
        if (m_shutdown) return MF_E_SHUTDOWN;
        queue = m_events;
    }
    return queue->GetEvent(flags, event);
}

IFACEMETHODIMP VeyroSource::QueueEvent(MediaEventType type, REFGUID ext, HRESULT status, const PROPVARIANT* pv)
{
    std::lock_guard<std::mutex> g(m_lock);
    return m_shutdown ? MF_E_SHUTDOWN : m_events->QueueEventParamVar(type, ext, status, pv);
}

IFACEMETHODIMP VeyroSource::CreatePresentationDescriptor(IMFPresentationDescriptor** pd)
{
    if (!pd) return E_POINTER;
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    return m_pd->Clone(pd);
}

IFACEMETHODIMP VeyroSource::GetCharacteristics(DWORD* characteristics)
{
    if (!characteristics) return E_POINTER;
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    *characteristics = MFMEDIASOURCE_IS_LIVE;
    return S_OK;
}

IFACEMETHODIMP VeyroSource::Start(IMFPresentationDescriptor* pd, const GUID* timeFormat, const PROPVARIANT* start)
{
    if (!pd) return E_INVALIDARG;
    if (timeFormat && *timeFormat != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;

    // Take the media type the caller selected.
    BOOL selected = FALSE;
    ComPtr<IMFStreamDescriptor> sd;
    HRESULT hr = pd->GetStreamDescriptorByIndex(0, &selected, &sd);
    if (FAILED(hr)) return hr;
    if (selected)
    {
        ComPtr<IMFMediaTypeHandler> from, to;
        ComPtr<IMFMediaType> type;
        if (SUCCEEDED(sd->GetMediaTypeHandler(&from)) && SUCCEEDED(from->GetCurrentMediaType(&type)) &&
            SUCCEEDED(m_stream->Descriptor()->GetMediaTypeHandler(&to)))
        {
            to->SetCurrentMediaType(type.Get());
        }
    }

    PROPVARIANT unk;
    PropVariantInit(&unk);
    unk.vt = VT_UNKNOWN;
    unk.punkVal = static_cast<IMFMediaStream*>(m_stream.Get());
    hr = m_events->QueueEventParamVar(m_started ? MEUpdatedStream : MENewStream, GUID_NULL, S_OK, &unk);
    if (FAILED(hr)) return hr;
    m_started = true;
    if (selected)
    {
        hr = m_stream->Start();
        if (FAILED(hr)) return hr;
    }
    PROPVARIANT time;
    PropVariantInit(&time);
    time.vt = VT_I8;
    time.hVal.QuadPart = MFGetSystemTime();
    (void)start;
    return m_events->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &time);
}

IFACEMETHODIMP VeyroSource::Stop()
{
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    HRESULT hr = m_stream->Stop();
    if (FAILED(hr)) return hr;
    return m_events->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
}

IFACEMETHODIMP VeyroSource::Shutdown()
{
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    m_shutdown = true;
    if (m_stream) m_stream->Shutdown();
    if (m_events) m_events->Shutdown();
    return S_OK;
}

IFACEMETHODIMP VeyroSource::GetSourceAttributes(IMFAttributes** attributes)
{
    if (!attributes) return E_POINTER;
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    return m_attributes.CopyTo(attributes);
}

IFACEMETHODIMP VeyroSource::GetStreamAttributes(DWORD id, IMFAttributes** attributes)
{
    if (!attributes) return E_POINTER;
    if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
    std::lock_guard<std::mutex> g(m_lock);
    if (m_shutdown) return MF_E_SHUTDOWN;
    return m_stream->GetAttributes(attributes);
}

// ── The activator Frame Server creates by CLSID ─────────────────────────────────────────────────

#define VEYRO_FORWARD(call) { return m_attributes ? m_attributes->call : E_OUTOFMEMORY; }

class __declspec(uuid("8A4F7E21-3C6B-4D59-9E1A-5B2C7D3F6A10")) VeyroActivate
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFActivate, IMFAttributes>>
{
public:
    VeyroActivate() { g_objects++; MFCreateAttributes(&m_attributes, 4); }
    ~VeyroActivate() { g_objects--; }

    IFACEMETHODIMP ActivateObject(REFIID riid, void** ppv) override
    {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        std::lock_guard<std::mutex> g(m_lock);
        if (!m_source)
        {
            HRESULT hr = MakeAndInitialize<VeyroSource>(&m_source);
            if (FAILED(hr)) return hr;
        }
        return m_source->QueryInterface(riid, ppv);
    }
    IFACEMETHODIMP ShutdownObject() override
    {
        std::lock_guard<std::mutex> g(m_lock);
        if (m_source) { m_source->Shutdown(); m_source.Reset(); }
        return S_OK;
    }
    IFACEMETHODIMP DetachObject() override
    {
        std::lock_guard<std::mutex> g(m_lock);
        m_source.Reset();
        return S_OK;
    }

    IFACEMETHODIMP GetItem(REFGUID k, PROPVARIANT* v) override VEYRO_FORWARD(GetItem(k, v))
    IFACEMETHODIMP GetItemType(REFGUID k, MF_ATTRIBUTE_TYPE* t) override VEYRO_FORWARD(GetItemType(k, t))
    IFACEMETHODIMP CompareItem(REFGUID k, REFPROPVARIANT v, BOOL* r) override VEYRO_FORWARD(CompareItem(k, v, r))
    IFACEMETHODIMP Compare(IMFAttributes* t, MF_ATTRIBUTES_MATCH_TYPE m, BOOL* r) override VEYRO_FORWARD(Compare(t, m, r))
    IFACEMETHODIMP GetUINT32(REFGUID k, UINT32* v) override VEYRO_FORWARD(GetUINT32(k, v))
    IFACEMETHODIMP GetUINT64(REFGUID k, UINT64* v) override VEYRO_FORWARD(GetUINT64(k, v))
    IFACEMETHODIMP GetDouble(REFGUID k, double* v) override VEYRO_FORWARD(GetDouble(k, v))
    IFACEMETHODIMP GetGUID(REFGUID k, GUID* v) override VEYRO_FORWARD(GetGUID(k, v))
    IFACEMETHODIMP GetStringLength(REFGUID k, UINT32* v) override VEYRO_FORWARD(GetStringLength(k, v))
    IFACEMETHODIMP GetString(REFGUID k, LPWSTR v, UINT32 s, UINT32* l) override VEYRO_FORWARD(GetString(k, v, s, l))
    IFACEMETHODIMP GetAllocatedString(REFGUID k, LPWSTR* v, UINT32* l) override VEYRO_FORWARD(GetAllocatedString(k, v, l))
    IFACEMETHODIMP GetBlobSize(REFGUID k, UINT32* s) override VEYRO_FORWARD(GetBlobSize(k, s))
    IFACEMETHODIMP GetBlob(REFGUID k, UINT8* b, UINT32 s, UINT32* l) override VEYRO_FORWARD(GetBlob(k, b, s, l))
    IFACEMETHODIMP GetAllocatedBlob(REFGUID k, UINT8** b, UINT32* s) override VEYRO_FORWARD(GetAllocatedBlob(k, b, s))
    IFACEMETHODIMP GetUnknown(REFGUID k, REFIID r, LPVOID* p) override VEYRO_FORWARD(GetUnknown(k, r, p))
    IFACEMETHODIMP SetItem(REFGUID k, REFPROPVARIANT v) override VEYRO_FORWARD(SetItem(k, v))
    IFACEMETHODIMP DeleteItem(REFGUID k) override VEYRO_FORWARD(DeleteItem(k))
    IFACEMETHODIMP DeleteAllItems() override VEYRO_FORWARD(DeleteAllItems())
    IFACEMETHODIMP SetUINT32(REFGUID k, UINT32 v) override VEYRO_FORWARD(SetUINT32(k, v))
    IFACEMETHODIMP SetUINT64(REFGUID k, UINT64 v) override VEYRO_FORWARD(SetUINT64(k, v))
    IFACEMETHODIMP SetDouble(REFGUID k, double v) override VEYRO_FORWARD(SetDouble(k, v))
    IFACEMETHODIMP SetGUID(REFGUID k, REFGUID v) override VEYRO_FORWARD(SetGUID(k, v))
    IFACEMETHODIMP SetString(REFGUID k, LPCWSTR v) override VEYRO_FORWARD(SetString(k, v))
    IFACEMETHODIMP SetBlob(REFGUID k, const UINT8* b, UINT32 s) override VEYRO_FORWARD(SetBlob(k, b, s))
    IFACEMETHODIMP SetUnknown(REFGUID k, IUnknown* u) override VEYRO_FORWARD(SetUnknown(k, u))
    IFACEMETHODIMP LockStore() override VEYRO_FORWARD(LockStore())
    IFACEMETHODIMP UnlockStore() override VEYRO_FORWARD(UnlockStore())
    IFACEMETHODIMP GetCount(UINT32* c) override VEYRO_FORWARD(GetCount(c))
    IFACEMETHODIMP GetItemByIndex(UINT32 i, GUID* k, PROPVARIANT* v) override VEYRO_FORWARD(GetItemByIndex(i, k, v))
    IFACEMETHODIMP CopyAllItems(IMFAttributes* d) override VEYRO_FORWARD(CopyAllItems(d))

private:
    std::mutex m_lock;
    ComPtr<IMFAttributes> m_attributes;
    ComPtr<VeyroSource> m_source;
};

CoCreatableClass(VeyroActivate);

// ── Exports ─────────────────────────────────────────────────────────────────────────────────────

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID* ppv)
{
    return Module<InProc>::GetModule().GetClassObject(rclsid, riid, ppv);
}

STDAPI DllCanUnloadNow()
{
    return (g_objects == 0 && Module<InProc>::GetModule().GetObjectCount() == 0) ? S_OK : S_FALSE;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(module);
    return TRUE;
}

/// Opens (or creates) the camera registration: Windows remembers it until VeyroVcamRemove.
static HRESULT WithCamera(HRESULT (*action)(IMFVirtualCamera*))
{
    HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
    if (SUCCEEDED(hr))
    {
        ComPtr<IMFVirtualCamera> camera;
        hr = MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_System,
            MFVirtualCameraAccess_CurrentUser, kFriendlyName, kSourceId, nullptr, 0, &camera);
        if (SUCCEEDED(hr)) hr = action(camera.Get());
        if (camera) camera->Shutdown();
        MFShutdown();
    }
    if (SUCCEEDED(init)) CoUninitialize();
    return hr;
}

/// Makes "Veyro Phone Camera" appear in apps. The COM class must already be registered in HKLM (setup).
extern "C" HRESULT __stdcall VeyroVcamRegister()
{
    return WithCamera([](IMFVirtualCamera* camera) { return camera->Start(nullptr); });
}

/// Takes the camera out of Windows.
extern "C" HRESULT __stdcall VeyroVcamRemove()
{
    return WithCamera([](IMFVirtualCamera* camera) { return camera->Remove(); });
}
