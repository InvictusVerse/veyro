namespace Veyro.Data.Contracts;

/// <summary>Renders QR codes as PNG bytes (ADR-030). The content may hold a pairing code: never log it.</summary>
public interface IQrCodeRenderer
{
    byte[] RenderPng(string content, int pixelsPerModule = 10);
}
