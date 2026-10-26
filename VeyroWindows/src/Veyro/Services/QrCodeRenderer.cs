using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using Windows.Storage.Streams;

namespace Veyro.Services;

/// <summary>
/// QR rendering with QRCoder's PngByteQRCode (ADR-030): a self-contained PNG writer, no GDI+.
/// The content can carry a pairing code, so it is never logged.
/// </summary>
public sealed class QrCodeRenderer : IQrCodeRenderer
{
    public byte[] RenderPng(string content, int pixelsPerModule = 10)
    {
        using var generator = new QRCodeGenerator();
        using QRCodeData data = generator.CreateQrCode(content, QRCodeGenerator.ECCLevel.M);
        using var png = new PngByteQRCode(data);
        return png.GetGraphic(pixelsPerModule);
    }

    /// <summary>Decodes PNG bytes for an Image control. UI thread.</summary>
    public static async Task<BitmapImage> ToBitmapAsync(byte[] png)
    {
        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(png);
            await writer.StoreAsync();
            await writer.FlushAsync();
            writer.DetachStream();
        }
        stream.Seek(0);
        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream);
        return bitmap;
    }
}
