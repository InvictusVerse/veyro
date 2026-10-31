# Creates Veyro's self-signed MSIX signing certificate once (ADR-075). The subject must stay
# "CN=Veyro" (Package.appxmanifest Publisher), or the package identity changes. The PFX, its
# random password and the public .cer go to $KeyDir, outside the repository; nothing is added
# to a Windows certificate store. Re-running refuses to overwrite an existing key.
param([string]$KeyDir = 'D:\Coding\Keys\Veyro-Windows')
$ErrorActionPreference = 'Stop'
$pfx = Join-Path $KeyDir 'veyro-signing.pfx'
if (Test-Path -LiteralPath $pfx) { throw "A signing key already exists: $pfx" }
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null

$rsa = [System.Security.Cryptography.RSA]::Create(3072)
$request = New-Object System.Security.Cryptography.X509Certificates.CertificateRequest(
    'CN=Veyro', $rsa, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
$request.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension($false, $false, 0, $true)))
$request.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509KeyUsageExtension(
    [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature, $true)))
$eku = New-Object System.Security.Cryptography.OidCollection
$null = $eku.Add((New-Object System.Security.Cryptography.Oid('1.3.6.1.5.5.7.3.3', 'Code Signing')))
$request.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension($eku, $false)))
$cert = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddYears(10))

$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = [Convert]::ToBase64String($bytes)
[IO.File]::WriteAllBytes($pfx, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, $password))
[IO.File]::WriteAllBytes((Join-Path $KeyDir 'veyro-signing.cer'), $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
Set-Content -LiteralPath (Join-Path $KeyDir 'pfx-password.txt') -Value $password -NoNewline -Encoding ASCII

# Only the current user may read the key folder.
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $KeyDir -AclObject $acl

Write-Output "Created $pfx"
Write-Output "Thumbprint $($cert.Thumbprint), valid until $($cert.NotAfter.ToString('yyyy-MM-dd'))"
