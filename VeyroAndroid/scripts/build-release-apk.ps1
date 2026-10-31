# Builds the signed release APK for a GitHub release (ADR-075). Run it yourself in a terminal:
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\Coding\Projects\veyro\VeyroAndroid\scripts\build-release-apk.ps1
# It asks for the key alias and passwords, passes them to Gradle for this one run only
# (environment variables of this process, no daemon), verifies the signature, and copies
# the APK and its SHA-256 to the release folder. Nothing about the key is written to disk.
param(
    [string]$Keystore = 'D:\Coding\Keys For Apk\key.jks',
    [string]$OutDir = 'D:\Coding\Releases\Veyro'
)
$ErrorActionPreference = 'Stop'
$env:TEMP = 'D:\CodingSoftwares\Temp'
$env:TMP = 'D:\CodingSoftwares\Temp'
$env:GRADLE_USER_HOME = 'D:\CodingSoftwares\GradleCache'
$android = Split-Path -Parent $PSScriptRoot
$sdk = 'D:\CodingSoftwares\Android_Sdk'

if (-not (Test-Path -LiteralPath $Keystore)) { throw "Keystore not found: $Keystore" }

function Read-Secret([string]$prompt) {
    $secure = Read-Host -Prompt $prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$alias = Read-Host -Prompt 'Key alias'
$storePass = Read-Secret 'Keystore password'
$keyPass = Read-Secret 'Key password (Enter = same as keystore)'
if ([string]::IsNullOrEmpty($keyPass)) { $keyPass = $storePass }

$env:VEYRO_RELEASE_STORE_FILE = $Keystore
$env:VEYRO_RELEASE_KEY_ALIAS = $alias
$env:VEYRO_RELEASE_STORE_PASSWORD = $storePass
$env:VEYRO_RELEASE_KEY_PASSWORD = $keyPass

$mutex = New-Object System.Threading.Mutex($false, 'VeyroGradleBuildLock')
Write-Output '[lock] waiting for the Gradle build lock...'
try { $null = $mutex.WaitOne() } catch [System.Threading.AbandonedMutexException] { }
try {
    Push-Location $android
    # --no-daemon: the passwords must not stay behind in a long-lived daemon's environment.
    & .\gradlew.bat --no-daemon --console=plain :app:assembleRelease
    $code = $LASTEXITCODE
    Pop-Location
} finally {
    Remove-Item Env:VEYRO_RELEASE_STORE_PASSWORD, Env:VEYRO_RELEASE_KEY_PASSWORD, Env:VEYRO_RELEASE_KEY_ALIAS, Env:VEYRO_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    $storePass = $null; $keyPass = $null
    $mutex.ReleaseMutex(); $mutex.Dispose()
}
if ($code -ne 0) { throw "Gradle failed with exit code $code" }

$apk = Get-ChildItem (Join-Path $android 'app\build\outputs\apk\release') -Filter '*.apk' | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $apk) { throw 'No release APK was produced.' }

# The newest build-tools folder that actually has apksigner (a partial 37.0.0 install has none).
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { [version]($_.Name -replace '[^\d.]', '') } | Select-Object -Last 1
if (-not $apksigner) { throw 'No build-tools folder contains apksigner.bat.' }
$apksigner = Join-Path $apksigner.FullName 'apksigner.bat'
$cert = & $apksigner verify --print-certs $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "Signature check failed:`n$cert" }
if ($cert -match 'CN=Android Debug') { throw 'The APK is debug-signed; it must not be published.' }

$version = (Select-String -Path (Join-Path $android 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$target = Join-Path $OutDir "Veyro-$version.apk"
Copy-Item -LiteralPath $apk.FullName -Destination $target -Force
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
Set-Content -LiteralPath "$target.sha256" -Value "$hash  $(Split-Path -Leaf $target)" -Encoding ASCII
Write-Output ''
Write-Output "Signed APK: $target"
Write-Output "SHA-256:    $hash"
Write-Output ($cert -split "`n" | Where-Object { $_ -match 'Signer #1 certificate (DN|SHA-256)' })
