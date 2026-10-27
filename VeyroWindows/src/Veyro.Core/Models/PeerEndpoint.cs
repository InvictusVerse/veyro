namespace Veyro.Core.Models;

public sealed record PeerEndpoint(string Host, int Port, string Source = "LAN");
