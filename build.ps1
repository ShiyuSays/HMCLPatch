# HMCLNoIPCheck build script.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Jdk "C:\Program Files\Java\jdk-17"
#
# Output: dist\HMCLNoIPCheck.jar
#
# The script needs a JDK 17+ (it compiles against the real HMCL jar so that the
# patched class is guaranteed to be API-compatible with the build it targets).

param(
    [string]$Jdk = "",
    [string]$HmclExe = "C:\Users\36049\Desktop\Lanucher\HMCL\HMCL-3.17.0.362.exe"
)

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $Root

$HmclJar = Join-Path $Root "build\HMCL-3.17.0.362.jar"
$ClassesPatch = Join-Path $Root "build\classes-patch"
$ClassesAgent = Join-Path $Root "build\classes-agent"
$Stage = Join-Path $Root "build\stage"
$Manifest = Join-Path $Root "build\MANIFEST.MF"
$DistJar = Join-Path $Root "dist\HMCLNoIPCheck.jar"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "FAILED: $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $output = & $Exe @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        Write-Host $output
        Fail "$What (exit code $exitCode)"
    }
    return $output
}

# ---------------------------------------------------------------------------
# 1. Locate a JDK
# ---------------------------------------------------------------------------

Write-Step "Locating a JDK 17+"

$candidates = New-Object System.Collections.Generic.List[string]
if ($Jdk) { $candidates.Add($Jdk) }
foreach ($known in @("C:\Program Files\Java\jdk-21", "C:\Program Files\Java\jdk-17", "C:\Program Files\Java\jdk-24", "C:\Program Files\Java\jdk-25")) {
    $candidates.Add($known)
}
if (Test-Path "C:\Program Files\Java") {
    Get-ChildItem "C:\Program Files\Java" -Directory |
        Where-Object { $_.Name -match '^jdk-(1[7-9]|2[0-9])' } |
        Sort-Object Name -Descending |
        ForEach-Object { $candidates.Add($_.FullName) }
}
if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

$Javac = $null
foreach ($candidate in $candidates) {
    if (-not $candidate) { continue }
    $javacPath = Join-Path $candidate "bin\javac.exe"
    if (Test-Path $javacPath) {
        $Javac = $javacPath
        break
    }
}
if (-not $Javac) { Fail "No JDK found. Pass -Jdk <path-to-jdk>." }

$JdkHome = Split-Path -Parent (Split-Path -Parent $Javac)
$JarTool = Join-Path $JdkHome "bin\jar.exe"
$Javap = Join-Path $JdkHome "bin\javap.exe"
Write-Host "JDK: $JdkHome"

# javac prints localized messages; force English so failures are readable here.
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"

# ---------------------------------------------------------------------------
# 2. Make sure the reference HMCL jar is available
# ---------------------------------------------------------------------------

Write-Step "Checking the reference HMCL jar"

if (-not (Test-Path $HmclJar)) {
    if (-not (Test-Path $HmclExe)) {
        Fail "Missing $HmclJar. Extract the HMCL jar first or pass -HmclExe <HMCL.exe>."
    }

    Write-Host "Extracting the jar appended to $(Split-Path -Leaf $HmclExe) ..."
    $bytes = [System.IO.File]::ReadAllBytes($HmclExe)
    $eocd = -1
    $limit = [Math]::Max(0, $bytes.Length - 66000)
    for ($i = $bytes.Length - 22; $i -ge $limit; $i--) {
        if ($bytes[$i] -eq 0x50 -and $bytes[$i + 1] -eq 0x4B -and $bytes[$i + 2] -eq 0x05 -and $bytes[$i + 3] -eq 0x06) {
            $eocd = $i
            break
        }
    }
    if ($eocd -lt 0) { Fail "No zip end-of-central-directory record in $HmclExe" }

    $cdSize = [BitConverter]::ToUInt32($bytes, $eocd + 12)
    $cdOffset = [BitConverter]::ToUInt32($bytes, $eocd + 16)
    $zipStart = $eocd - $cdSize - $cdOffset
    if ($zipStart -le 0) { Fail "Could not locate the embedded jar (offset $zipStart)" }

    $jarBytes = New-Object byte[] ($bytes.Length - $zipStart)
    [Array]::Copy($bytes, $zipStart, $jarBytes, 0, $jarBytes.Length)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $HmclJar) | Out-Null
    [System.IO.File]::WriteAllBytes($HmclJar, $jarBytes)
}

$jarInfo = Get-Item $HmclJar
Write-Host ("Reference jar: {0} ({1:N0} bytes)" -f $jarInfo.Name, $jarInfo.Length)

# ---------------------------------------------------------------------------
# 3. Compile the patched RegionUtils and the agent
# ---------------------------------------------------------------------------

Write-Step "Compiling the patched RegionUtils"

Remove-Item -Recurse -Force $ClassesPatch -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $ClassesPatch | Out-Null

Invoke-Native $Javac @(
    "--release", "17",
    "-encoding", "UTF-8",
    "-nowarn",
    "-classpath", $HmclJar,
    "-sourcepath", (Join-Path $Root "stubs"),
    "-implicit:none",
    "-d", $ClassesPatch,
    (Join-Path $Root "src\org\jackhuang\hmcl\util\RegionUtils.java")
) "Compiling RegionUtils" | Out-Null

Write-Step "Compiling the agent"

Remove-Item -Recurse -Force $ClassesAgent -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $ClassesAgent | Out-Null

$agentSources = @(Get-ChildItem (Join-Path $Root "src\hmclpatch") -Filter *.java | ForEach-Object { $_.FullName })
$agentArguments = @(
    "--release", "17",
    "-encoding", "UTF-8",
    "-nowarn",
    "-d", $ClassesAgent
) + $agentSources
Invoke-Native $Javac $agentArguments "Compiling the agent" | Out-Null

Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
# 4. Verify that the patched class is a drop-in replacement
# ---------------------------------------------------------------------------

Write-Step "Verifying binary compatibility with the original RegionUtils"

function Get-JavapMembers([string]$ClassPath, [string]$ClassName) {
    $lines = & $Javap -p -classpath $ClassPath $ClassName 2>$null
    $members = @()
    foreach ($line in $lines) {
        if ($line -match '^  \S') {
            $members += (($line.Trim()) -replace '\s+', ' ')
        }
    }
    return ($members | Sort-Object -Unique)
}

$originalMembers = Get-JavapMembers $HmclJar "org.jackhuang.hmcl.util.RegionUtils"
$patchedMembers = Get-JavapMembers $ClassesPatch "org.jackhuang.hmcl.util.RegionUtils"

$onlyOriginal = Compare-Object -ReferenceObject $originalMembers -DifferenceObject $patchedMembers |
    Where-Object { $_.SideIndicator -eq '<=' } | ForEach-Object { $_.InputObject }
$onlyPatched = Compare-Object -ReferenceObject $originalMembers -DifferenceObject $patchedMembers |
    Where-Object { $_.SideIndicator -eq '=>' } | ForEach-Object { $_.InputObject }

if ($onlyOriginal -or $onlyPatched) {
    Write-Host "Members present only in the original class:" -ForegroundColor Yellow
    $onlyOriginal | ForEach-Object { Write-Host "  $_" }
    Write-Host "Members present only in the patched class:" -ForegroundColor Yellow
    $onlyPatched | ForEach-Object { Write-Host "  $_" }
    Fail "The patched class is not structurally identical, retransformation would be rejected."
}

Write-Host ("Member list identical: {0} members" -f $originalMembers.Count)

$patchedClassFile = Join-Path $ClassesPatch "org\jackhuang\hmcl\util\RegionUtils.class"
$classBytes = [System.IO.File]::ReadAllBytes($patchedClassFile)
$majorVersion = $classBytes[6] * 256 + $classBytes[7]
if ($majorVersion -ne 61) { Fail "Unexpected class file version $majorVersion (expected 61 = Java 17)" }
Write-Host "Class file version: $majorVersion (Java 17)"

$callSite = & $Javap -p -c -classpath $ClassesPatch "org.jackhuang.hmcl.util.RegionUtils" 2>$null |
    Select-String -Pattern "public static boolean isIsChinaMainlandNetwork" -Context 0,6
Write-Host ""
Write-Host "Patched method:"
$callSite | ForEach-Object { $_.ToString() }

# ---------------------------------------------------------------------------
# 5. Package
# ---------------------------------------------------------------------------

Write-Step "Packaging dist\HMCLNoIPCheck.jar"

Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "hmclpatch") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "patch\org\jackhuang\hmcl\util") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "dist") | Out-Null

Copy-Item (Join-Path $ClassesAgent "hmclpatch\*.class") (Join-Path $Stage "hmclpatch") -Force
Copy-Item $patchedClassFile (Join-Path $Stage "patch\org\jackhuang\hmcl\util\RegionUtils.class") -Force
Copy-Item (Join-Path $Root "assets\README.txt") (Join-Path $Stage "README.txt") -Force
Copy-Item (Join-Path $Root "assets\README.txt") (Join-Path $Root "dist\README.txt") -Force

$manifestText = @"
Manifest-Version: 1.0
Implementation-Title: HMCLNoIPCheck
Implementation-Version: 1.1.0
Premain-Class: hmclpatch.NoIPCheckAgent
Agent-Class: hmclpatch.NoIPCheckAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: false
Main-Class: hmclpatch.Main
Created-By: HMCLNoIPCheck build script

"@
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($Manifest, ($manifestText -replace "`r?`n", "`r`n"), $utf8NoBom)

Remove-Item $DistJar -Force -ErrorAction SilentlyContinue
Invoke-Native $JarTool @(
    "--create",
    "--file", $DistJar,
    "--manifest", $Manifest,
    "-C", $Stage, "."
) "Packaging the jar" | Out-Null

# The start scripts are only useful on Windows and are written in GBK for cmd.exe.
foreach ($bat in Get-ChildItem (Join-Path $Root "assets") -Filter *.bat) {
    $batText = [System.IO.File]::ReadAllText($bat.FullName, $utf8NoBom)
    $batWindowsText = ($batText -replace "`r?`n", "`r`n")
    [System.IO.File]::WriteAllText((Join-Path $Root ("dist\" + $bat.Name)), $batWindowsText, [System.Text.Encoding]::GetEncoding(936))
}

# ---------------------------------------------------------------------------
# 6. Final checks
# ---------------------------------------------------------------------------

Write-Step "Checking the packaged jar"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($DistJar)
try {
    $entries = $zip.Entries | ForEach-Object { $_.FullName }
    Write-Host ("Entries: {0}" -f $entries.Count)
    foreach ($required in @(
        "META-INF/MANIFEST.MF",
        "hmclpatch/NoIPCheckAgent.class",
        "hmclpatch/Main.class",
        "hmclpatch/InjectionWindow.class",
        "hmclpatch/Injector.class",
        "hmclpatch/LaunchSupport.class",
        "patch/org/jackhuang/hmcl/util/RegionUtils.class"
    )) {
        if ($entries -notcontains $required) { Fail "Missing jar entry: $required" }
    }
    if ($entries -contains "org/jackhuang/hmcl/util/RegionUtils.class") {
        Fail "The patched class must not be stored at its canonical path (it would shadow the agent)."
    }

    $manifestEntry = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/MANIFEST.MF" }
    $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
    $manifestContent = $reader.ReadToEnd()
    $reader.Close()
    foreach ($attribute in @("Premain-Class: hmclpatch.NoIPCheckAgent", "Agent-Class: hmclpatch.NoIPCheckAgent", "Can-Retransform-Classes: true", "Main-Class: hmclpatch.Main")) {
        if ($manifestContent -notmatch [regex]::Escape($attribute)) { Fail "Manifest is missing: $attribute" }
    }
}
finally {
    $zip.Dispose()
}

$hash = (Get-FileHash $DistJar -Algorithm SHA256).Hash
$size = (Get-Item $DistJar).Length

Write-Host ""
Write-Host "BUILD OK" -ForegroundColor Green
Write-Host ("  file  : {0}" -f $DistJar)
Write-Host ("  size  : {0:N0} bytes" -f $size)
Write-Host ("  sha256: {0}" -f $hash.ToLower())
Write-Host ""
Write-Host "Usage:"
Write-Host "  set HMCL_JAVA_OPTS=-javaagent:`"$DistJar`""
Write-Host "  HMCL-3.17.0.362.exe"
