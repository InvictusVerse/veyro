# Builds Veyro-Setup-<version>.exe for a GitHub release (ADR-075). Not a publish step.
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\Coding\Projects\veyro\VeyroWindows\installer\build-installer.ps1
# 1. Release MSIX, x64, self-contained .NET (users need not install .NET), signed with the key
#    from new-signing-cert.ps1 (outside the repo).
# 2. NSIS wraps it with the Windows App Runtime and the public certificate.
# 3. The setup is signed with the same key; files and SHA-256 go to $OutDir.
param(
    [string]$KeyDir = 'D:\Coding\Keys\Veyro-Windows',
    [string]$OutDir = 'D:\Coding\Releases\Veyro'
)
$ErrorActionPreference = 'Stop'
$env:TEMP = 'D:\CodingSoftwares\Temp'
$env:TMP = 'D:\CodingSoftwares\Temp'
$env:NUGET_PACKAGES = 'D:\CodingSoftwares\NuGetCache\packages'
$env:DOTNET_CLI_HOME = 'D:\CodingSoftwares\DotNetHome'

$here = $PSScriptRoot
$src = Join-Path (Split-Path -Parent $here) 'src'
$pfx = Join-Path $KeyDir 'veyro-signing.pfx'
$cer = Join-Path $KeyDir 'veyro-signing.cer'
if (-not (Test-Path -LiteralPath $pfx)) { throw "No signing key. Run new-signing-cert.ps1 first." }
$password = Get-Content -LiteralPath (Join-Path $KeyDir 'pfx-password.txt') -Raw
$thumbprint = (New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cer)).Thumbprint
$version = ([xml](Get-Content (Join-Path $src 'Veyro\Package.appxmanifest'))).Package.Identity.Version
$makensis = 'C:\Program Files (x86)\NSIS\makensis.exe'
$signtool = 'C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe'
$packageDir = Join-Path $env:TEMP 'veyro-release-msix'
if (Test-Path $packageDir) { Get-ChildItem $packageDir -File -Recurse | Remove-Item -Force }

$mutex = New-Object System.Threading.Mutex($false, 'VeyroDotnetBuildLock')
Write-Output '[lock] waiting for the .NET build lock...'
try { $null = $mutex.WaitOne() } catch [System.Threading.AbandonedMutexException] { }
try {
    Push-Location $src
    & dotnet build Veyro\Veyro.csproj -c Release -p:Platform=x64 -p:RuntimeIdentifier=win-x64 -p:SelfContained=true `
        -p:GenerateAppxPackageOnBuild=true -p:AppxBundle=Never -p:UapAppxPackageBuildMode=SideloadOnly `
        -p:AppxPackageDir="$packageDir\\" -p:AppxPackageSigningEnabled=false -nologo -v:minimal 2>&1 |
        ForEach-Object { "$_" } | Where-Object { $_ -match ' error |Build succeeded|\.msix' }
    $code = $LASTEXITCODE
    Pop-Location
} finally {
    $mutex.ReleaseMutex(); $mutex.Dispose()
}
if ($code -ne 0) { throw "The release build failed ($code)." }

$msix = Get-ChildItem $packageDir -Recurse -Filter 'Veyro_*.msix' | Where-Object FullName -notmatch 'Dependencies' | Select-Object -First 1
$dependency = Get-ChildItem $packageDir -Recurse -Filter 'Microsoft.WindowsAppRuntime*.msix' | Where-Object FullName -match '\\x64\\' | Select-Object -First 1
if (-not $msix -or -not $dependency) { throw 'The MSIX or the Windows App Runtime package is missing.' }
# Signed here rather than by MSBuild, whose signing task cannot import a key that is not in a store.
& $signtool sign /fd SHA256 /f $pfx /p $password $msix.FullName | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Signing the MSIX failed ($LASTEXITCODE)." }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$setup = Join-Path $OutDir "Veyro-Setup-$version.exe"
& $makensis /V2 "/DVERSION=$version" "/DMSIX=$($msix.FullName)" "/DDEPENDENCY=$($dependency.FullName)" `
    "/DCERT=$cer" "/DTHUMBPRINT=$thumbprint" "/DOUTFILE=$setup" "/DICON=$(Join-Path $src 'Veyro\Assets\Veyro.ico')" `
    (Join-Path $here 'veyro-setup.nsi')
if ($LASTEXITCODE -ne 0) { throw "makensis failed ($LASTEXITCODE)." }

& $signtool sign /fd SHA256 /f $pfx /p $password $setup | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Signing the setup failed ($LASTEXITCODE)." }
$password = $null

Copy-Item -LiteralPath $cer -Destination (Join-Path $OutDir 'Veyro-publisher.cer') -Force
$hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash
Set-Content -LiteralPath "$setup.sha256" -Value "$hash  $(Split-Path -Leaf $setup)" -Encoding ASCII
Get-ChildItem $packageDir -File -Recurse | Remove-Item -Force
Write-Output "Setup:   $setup ($([math]::Round((Get-Item $setup).Length / 1MB, 1)) MB)"
Write-Output "SHA-256: $hash"
