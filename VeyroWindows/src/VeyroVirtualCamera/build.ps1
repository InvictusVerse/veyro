# Builds VeyroVirtualCamera.dll (x64, static CRT) with the MSVC tools in D:\Microsoft\VisualStudio
# and the Windows SDK, without MSBuild. Output: bin\x64\VeyroVirtualCamera.dll, which Veyro.csproj
# packages as VirtualCamera\VeyroVirtualCamera.dll. Temp files stay in obj\ (nothing on C:).
param([string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$msvcRoot = 'D:\Microsoft\VisualStudio\VC\Tools\MSVC'
$vc = Get-ChildItem -LiteralPath $msvcRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$sdk = 'C:\Program Files (x86)\Windows Kits\10'
$ver = Get-ChildItem -LiteralPath "$sdk\Include" -Directory | Where-Object { Test-Path "$($_.FullName)\um\mfvirtualcamera.h" } | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty Name
if (-not $vc -or -not $ver) { throw 'MSVC or a Windows SDK with mfvirtualcamera.h was not found.' }
$obj = Join-Path $here 'obj\x64'
$bin = Join-Path $here 'bin\x64'
New-Item -ItemType Directory -Force -Path $obj, $bin | Out-Null
$env:INCLUDE = "$vc\include;$sdk\Include\$ver\ucrt;$sdk\Include\$ver\um;$sdk\Include\$ver\shared;$sdk\Include\$ver\winrt"
$env:LIB = "$vc\lib\x64;$sdk\Lib\$ver\ucrt\x64;$sdk\Lib\$ver\um\x64"
$env:TEMP = $obj; $env:TMP = $obj
[string[]]$opt = if ($Configuration -eq 'Debug') { '/Od', '/Zi' } else { '/O2' }
& "$vc\bin\Hostx64\x64\cl.exe" /nologo /LD /MT /EHsc /std:c++17 /W4 /permissive- /DUNICODE /D_UNICODE @opt `
    "/Fo$obj\" "/Fe$bin\VeyroVirtualCamera.dll" "$here\VeyroVirtualCamera.cpp" `
    /link /DEF:"$here\VeyroVirtualCamera.def" "/IMPLIB:$obj\VeyroVirtualCamera.lib" `
    mfplat.lib mfuuid.lib mfsensorgroup.lib advapi32.lib ole32.lib uuid.lib runtimeobject.lib
if ($LASTEXITCODE -ne 0) { throw "cl.exe failed with $LASTEXITCODE" }
Get-Item "$bin\VeyroVirtualCamera.dll"
