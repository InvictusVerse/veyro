# Renders the MSIX tile/splash PNGs for VeyroWindows from tools/branding/icon.svg.
# Uses headless Microsoft Edge as the SVG rasterizer (no extra install).
# All scratch output stays under <repo>/scratch (git-ignored), never on C:.
#
#   powershell -File tools\branding\render-windows-assets.ps1

$ErrorActionPreference = 'Stop'
$repo    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$svg     = Join-Path $PSScriptRoot 'icon.svg'
$assets  = Join-Path $repo 'VeyroWindows\src\Veyro\Assets'
$scratch = Join-Path $repo 'scratch\branding'
$edge    = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"

if (-not (Test-Path $edge)) { throw "Microsoft Edge not found at $edge" }
New-Item -ItemType Directory -Force $scratch | Out-Null
Copy-Item $svg (Join-Path $scratch 'icon.svg') -Force

# The artwork occupies roughly x 123..940, y 218..793 of the 1024 canvas (plus the
# monitor drop shadow). This square viewBox crops the empty margin so small tiles
# stay legible: centre (531.5, 515), side 860.
$markViewBox = '101.5 85 860 860'

# name, width, height, mark size in px (square, centred)
$targets = @(
    @('Square44x44Logo.png',     44,  44,  44),
    @('StoreLogo.png',           50,  50,  50),
    @('Square150x150Logo.png',  150, 150, 120),
    @('Wide310x150Logo.png',    310, 150, 120),
    @('SplashScreen.png',       620, 300, 240)
)

# Taskbar, Start list and Alt+Tab use Square44x44Logo.targetsize-N. Without the
# "unplated" variants Windows draws the icon small on a coloured (accent/blue) plate.
foreach ($n in 16, 20, 24, 30, 32, 36, 40, 48, 60, 64, 72, 80, 96, 256) {
    foreach ($q in 'altform-unplated', 'altform-lightunplated') {
        $targets += ,@("Square44x44Logo.targetsize-${n}_$q.png", $n, $n, $n)
    }
    $targets += ,@("Square44x44Logo.targetsize-$n.png", $n, $n, $n)
}

$svgText = (Get-Content $svg -Raw) -replace 'viewBox="0 0 1024 1024"', "viewBox=""$markViewBox"""
Set-Content (Join-Path $scratch 'mark.svg') $svgText -Encoding UTF8

foreach ($t in $targets) {
    $name, $w, $h, $m = $t
    $html = Join-Path $scratch "$name.html"
    @"
<!doctype html><html><head><style>
html,body{margin:0;width:${w}px;height:${h}px;background:transparent;overflow:hidden}
body{display:flex;align-items:center;justify-content:center}
img{width:${m}px;height:${m}px}
</style></head><body><img src="mark.svg"></body></html>
"@ | Set-Content $html -Encoding UTF8

    $out = Join-Path $scratch $name
    if (Test-Path $out) { Remove-Item $out }
    & $edge --headless --disable-gpu --hide-scrollbars --no-first-run `
        --default-background-color=00000000 `
        "--user-data-dir=$(Join-Path $scratch 'edge-profile')" `
        "--window-size=$w,$h" "--screenshot=$out" ("file:///" + ($html -replace '\\','/')) 2>$null | Out-Null

    # Edge returns before the file is flushed on some machines.
    for ($i = 0; $i -lt 50 -and -not (Test-Path $out); $i++) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path $out)) { throw "Edge did not produce $name" }
    Copy-Item $out (Join-Path $assets $name) -Force
    "rendered $name ${w}x${h}"
}

Remove-Item (Join-Path $scratch 'edge-profile') -Recurse -Force -ErrorAction SilentlyContinue
