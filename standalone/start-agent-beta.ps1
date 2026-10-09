$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$installDir = Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent-Beta'
$runtimeDir = Join-Path $installDir 'runtime'
$jarPath = Join-Path $installDir 'solarminer-pc-agent-standalone.jar'
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

Write-Host 'Checking for the latest SolarMiner PC-Agent beta...'
$headers = @{ 'User-Agent' = 'SolarMiner-PC-Agent-Beta-Launcher' }
$releases = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases?per_page=100'
$release = $releases |
    Where-Object { $_.prerelease -and $_.tag_name -like 'pc-agent-beta-*' } |
    Sort-Object published_at -Descending |
    Select-Object -First 1
if (-not $release) { throw 'No SolarMiner PC-Agent beta release is available.' }

$jarAsset = $release.assets | Where-Object name -eq 'solarminer-pc-agent-standalone.jar' | Select-Object -First 1
$hashAsset = $release.assets | Where-Object name -eq 'solarminer-pc-agent-standalone.jar.sha256' | Select-Object -First 1
if (-not $jarAsset -or -not $hashAsset) { throw 'The latest beta release is missing the Agent JAR or its SHA-256 file.' }

$tempHash = Join-Path $installDir 'solarminer-pc-agent-standalone.jar.sha256.download'
try {
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $hashAsset.browser_download_url -OutFile $tempHash
    $hashText = (Get-Content -LiteralPath $tempHash -Raw).Trim()
    $hashMatch = [regex]::Match($hashText, '^([0-9a-fA-F]{64})\s+\*?solarminer-pc-agent-standalone\.jar$')
    if (-not $hashMatch.Success) { throw 'The PC-Agent beta SHA-256 file has an invalid format.' }
    $expectedHash = $hashMatch.Groups[1].Value.ToLowerInvariant()
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $tempHash
}

$currentHash = if (Test-Path -LiteralPath $jarPath) { (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant() } else { '' }
if ($currentHash -ne $expectedHash) {
    Write-Host "Downloading PC-Agent beta $($release.tag_name)..."
    $tempJar = "$jarPath.download"
    try {
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $jarAsset.browser_download_url -OutFile $tempJar
        $downloadHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $tempJar).Hash.ToLowerInvariant()
        if ($downloadHash -ne $expectedHash) { throw 'PC-Agent beta JAR SHA-256 validation failed.' }
        Move-Item -Force -LiteralPath $tempJar -Destination $jarPath
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $tempJar
    }
} else {
    Write-Host "PC-Agent beta $($release.tag_name) is already downloaded."
}

$javaExe = Join-Path $runtimeDir 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    Write-Host 'Downloading a Java 21 runtime...'
    $assets = Invoke-RestMethod -Headers $headers -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jre&jvm_impl=hotspot&os=windows&page_size=1&vendor=eclipse'
    $package = $assets[0].binary.package
    if (-not $package.link -or -not $package.checksum -or -not $package.name) { throw 'Adoptium did not provide a Windows x64 JRE package and checksum.' }
    $runtimeZip = Join-Path $installDir $package.name
    $runtimeTemp = "$runtimeZip.download"
    try {
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $package.link -OutFile $runtimeTemp
        $runtimeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $runtimeTemp).Hash.ToLowerInvariant()
        if ($runtimeHash -ne $package.checksum.ToLowerInvariant()) { throw 'Java runtime SHA-256 validation failed.' }
        Move-Item -Force -LiteralPath $runtimeTemp -Destination $runtimeZip
        $extractDir = Join-Path $installDir 'runtime-extract'
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -LiteralPath $extractDir
        Expand-Archive -LiteralPath $runtimeZip -DestinationPath $extractDir -Force
        $extractedHome = Get-ChildItem -LiteralPath $extractDir -Directory | Select-Object -First 1
        if (-not $extractedHome) { throw 'The Java runtime archive was empty.' }
        if (Test-Path -LiteralPath $runtimeDir) { Remove-Item -Recurse -Force -LiteralPath $runtimeDir }
        Move-Item -LiteralPath $extractedHome.FullName -Destination $runtimeDir
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $runtimeTemp, $runtimeZip
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -LiteralPath (Join-Path $installDir 'runtime-extract')
    }
    if (-not (Test-Path -LiteralPath $javaExe)) { throw 'Java runtime installation did not produce bin\java.exe.' }
}

Write-Host 'Starting SolarMiner PC-Agent beta. Open http://127.0.0.1:8084/ in your browser.'
Push-Location $installDir
try {
    & $javaExe -jar $jarPath '--solarminer.agent.standalone=true' '--server.address=0.0.0.0'
    if ($LASTEXITCODE -ne 0) { throw "PC-Agent beta exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
}
