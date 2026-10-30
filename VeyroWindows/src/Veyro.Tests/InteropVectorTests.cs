using System.Text;
using System.Text.Json;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives the shared interop vectors in <c>protocol/testvectors/</c>.
///
/// These are the only tests in this project whose expected values were produced independently of
/// this implementation. Everything else asserts that the C# agrees with itself or with one
/// person's reading of the Kotlin. Per <c>protocol/testvectors/README.md</c>: a vector that only
/// one platform passes is not a passing vector — it is a bug report about the other platform.
/// </summary>
public sealed class InteropVectorTests
{
    private static string VectorRoot
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
                dir = dir.Parent;

            Assert.True(dir is not null, "could not locate protocol/testvectors from the test output directory");
            return Path.Combine(dir!.FullName, "protocol", "testvectors");
        }
    }

    private static JsonDocument Load(params string[] relative)
        => JsonDocument.Parse(File.ReadAllText(Path.Combine(VectorRoot, Path.Combine(relative))));

    // VTC1 (frames/vtc1-frames.json) was retired with link protocol v2 (ADR-058); VTC3 is pinned by Vtc3VectorTests.

    [Fact]
    public void DeviceId_MatchesIdentityVectors()
    {
        using var doc = Load("identity", "device-id.json");

        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
        {
            byte[] pk = Convert.FromHexString(v.GetProperty("input").GetProperty("staticPublicKeyHex").GetString()!);
            var expected = v.GetProperty("expected");

            string actualId = DeviceId.FromPublicKey(pk).Value;
            Assert.Equal(expected.GetProperty("deviceId").GetString(), actualId);

            // The vector's fingerprint is space-separated upper-case hex of deviceIdBytes[0..7].
            byte[] idBytes = Convert.FromHexString(expected.GetProperty("deviceIdBytesHex").GetString()!);
            string fingerprint = string.Join(" ",
                idBytes.Take(8).Select(b => b.ToString("X2")));
            Assert.Equal(expected.GetProperty("fingerprint").GetString(), fingerprint);

            // And the id must be a 26-character Crockford Base32 rendering of those 16 bytes.
            Assert.Equal(26, actualId.Length);
            Assert.Equal(idBytes, Crockford32.Decode(actualId).Take(16).ToArray());
        }
    }
}
