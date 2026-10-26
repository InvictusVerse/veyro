using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Link;
using Xunit;

namespace Veyro.Tests;

public sealed class SecureLinkSessionTests
{
    [Fact]
    public void SessionFrame_UsesAuthenticatedEncryptedWireFormat()
    {
        byte[] key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        string json = LinkJson.Encode(new LinkEnvelope
        {
            MsgId = "m1", From = "peer", Seq = 1, Ts = 1,
            Payload = new LinkMessage.Ping("n1")
        });
        SealedFrame frame = FrameCrypto.Seal(json, key, encrypt: true, "peer");

        string? opened = FrameCrypto.Open(frame, key);
        Assert.Equal(json, opened);
        Assert.NotEqual(json, Encoding.UTF8.GetString(frame.Body));

        frame.Body[0] ^= 1;
        Assert.Null(FrameCrypto.Open(frame, key));
    }

    [Fact]
    public void PairingFrame_IsAuthenticatedButNotEncrypted()
    {
        byte[] key = FrameCrypto.DeriveBootstrapKey("123456", "a", "b");
        SealedFrame frame = FrameCrypto.Seal("payload", key, encrypt: false, "a");
        Assert.Equal("payload", FrameCrypto.Open(frame, key));
        Assert.Equal("payload", Encoding.UTF8.GetString(frame.Body));
    }
}