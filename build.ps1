# =====================================================================
#  Blur - build the mod for every supported Minecraft version plus the
#  desktop app, then package everything into ../Blur-Pack.
#
#  Usage:  .\build.ps1            (from the repo root)
# =====================================================================
$ErrorActionPreference = "Stop"

$repo = $PSScriptRoot
$pack = Join-Path (Split-Path $repo -Parent) "Blur-Pack"
$mods = Join-Path $pack "mods"

# Minecraft version -> Fabric API version.
# Newer releases: https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml
$targets26 = @(
  @{ mc = "26.1";   api = "0.145.1+26.1"   },
  @{ mc = "26.1.1"; api = "0.145.4+26.1.1" },
  @{ mc = "26.1.2"; api = "0.155.2+26.1.2" },
  @{ mc = "26.2";   api = "0.155.2+26.2"   }
)

function Say($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

if (Get-Process javaw, java -ErrorAction SilentlyContinue) {
  Write-Host "NOTE: Minecraft is running - close it before installing the new jars." -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path $mods | Out-Null

Say "Mod: 1.21.11 (obfuscated build)"
Push-Location (Join-Path $repo "mod-1.21.11")
& .\gradlew.bat build --console=plain | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"
Pop-Location

foreach ($t in $targets26) {
  Say "Mod: $($t.mc) (unobfuscated build)"
  Push-Location (Join-Path $repo "mod-26x")
  & .\gradlew.bat build --console=plain `
      "-Pminecraft_version=$($t.mc)" "-Pfabric_api_version=$($t.api)" |
    Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"
  Pop-Location
}

$appDir = Join-Path $repo "app"
if (-not (Test-Path $appDir)) {
  Say "Desktop app - SKIPPED (source not present)"
  Write-Host "The app source is not published in this repository; the mod jars above" -ForegroundColor Yellow
  Write-Host "are complete on their own. Get the prebuilt app from blurstats.com." -ForegroundColor Yellow
  Say "Done - jars in $mods"
  Get-ChildItem $mods | Select-Object Name, @{N = 'KB'; E = { [math]::Round($_.Length / 1KB, 1) } } | Format-Table -AutoSize
  return
}

Say "Desktop app"
Push-Location $appDir
if (-not (Test-Path "node_modules")) { & npm install --silent }
# --embed-resources bakes the UI into the exe, so it ships as ONE standalone file
# (no resources.neu next to it).
& npx --yes @neutralinojs/neu build --release --embed-resources | Select-String "generated"
& node seticon.js      # `neu build` strips the exe icon, so re-embed it
Pop-Location

Say "Packaging"
Remove-Item (Join-Path $mods "*.jar") -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $repo "mod-1.21.11\build\libs\blur-1.0.0+1.21.11.jar") $mods -Force
Get-ChildItem (Join-Path $repo "mod-26x\build\libs\*.jar") |
  Where-Object { $_.Name -notlike "*sources*" } |
  ForEach-Object { Copy-Item $_.FullName $mods -Force }

Copy-Item (Join-Path $repo "app\dist\Blur\Blur-win_x64.exe") (Join-Path $pack "Blur.exe") -Force
# Blur.exe embeds the Neutralino runtime (MIT), whose notice must ship with copies.
Copy-Item (Join-Path $repo "THIRD-PARTY-NOTICES.md") (Join-Path $pack "THIRD-PARTY-NOTICES.txt") -Force
# Ship the licence alongside the build so users know the terms.
Copy-Item (Join-Path $repo "LICENSE.md") (Join-Path $pack "LICENSE.txt") -Force
# resources are embedded in the exe now; clear out any leftovers from older builds
Remove-Item (Join-Path $pack "resources.neu"), (Join-Path $pack ".tmp"), `
  (Join-Path $pack "neutralinojs.log") -Recurse -Force -ErrorAction SilentlyContinue

Say "Done -> $pack"
Get-ChildItem $mods | Select-Object Name, @{N = 'KB'; E = { [math]::Round($_.Length / 1KB, 1) } } | Format-Table -AutoSize
