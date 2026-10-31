# Run by the Veyro setup (veyro-setup.nsi) from its temporary folder. Installs the signed Veyro
# MSIX for the current user. A developer registration of the same package (Visual Studio or a
# loose-file deploy) blocks a signed install, so it is removed first while keeping its data.
param([Parameter(Mandatory = $true)][string]$Package)
$ErrorActionPreference = 'Stop'
try {
    $existing = Get-AppxPackage -Name 'Veyro.Windows'
    if ($existing -and $existing.IsDevelopmentMode) {
        Remove-AppxPackage -Package $existing.PackageFullName -PreserveApplicationData
    }
    Add-AppxPackage -Path $Package -ForceApplicationShutdown -ForceUpdateFromAnyVersion
    exit 0
}
catch {
    Write-Output $_.Exception.Message
    exit 1
}
