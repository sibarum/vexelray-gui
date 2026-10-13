<#
  Captures all 16 demo chapters into <OutDir>, then, given -Baseline, diffs them against it with CaptureDiff.
  Build first: mvn -q install (the demo's target\classes and the harness jar must exist).

  A change to how things are drawn is checked against a baseline taken from the code before it, not one kept in the
  repository: capture settles, so the same commit captures the same pictures every time, and a kept baseline would go
  stale with every change meant to move a pixel.

    .\capture-chapters.ps1 target\before                       # on the commit before the change
    .\capture-chapters.ps1 target\after -Baseline target\before [-Tolerance 0]
#>
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Baseline,
    [int]$Tolerance = 0
)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\JDK\graalvm-jdk-25.0.3+9.1' }
$env:PATH = "$env:JAVA_HOME\bin;C:\JDK\apache-maven-3.9.16\bin;$env:PATH"
Set-Location $PSScriptRoot

$cpFile = Join-Path $PSScriptRoot 'target\runtime.classpath'
& mvn -q dependency:build-classpath "-Dmdep.outputFile=$cpFile" '-Dmdep.includeScope=runtime'
if ($LASTEXITCODE -ne 0) { throw 'could not build the runtime classpath' }
$cp = (Get-Content $cpFile -Raw).Trim() + ';target\classes'

New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
foreach ($n in 0..15) {
    $out = Join-Path $OutDir "chapter-$n.png"
    & java --enable-native-access=ALL-UNNAMED '-Dautomation=off' -classpath $cp dev.vexelray.gui.demo.Demo --capture $out $n 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) { throw "chapter $n did not capture" }
    Write-Host "captured chapter $n"
}

if ($Baseline) {
    $harness = Get-ChildItem (Join-Path $PSScriptRoot '..\vexelray-gui-harness\target') -Filter 'vexelray-gui-harness-*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc|tests' } | Select-Object -First 1
    if (-not $harness) { throw 'build vexelray-gui-harness first: mvn -q -pl vexelray-gui-harness install' }
    & java -classpath $harness.FullName dev.vexelray.gui.harness.CaptureDiff $Baseline $OutDir $Tolerance
    exit $LASTEXITCODE
}
