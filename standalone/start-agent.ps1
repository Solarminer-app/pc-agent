param([switch]$Bootstrap)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$installDir = Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent'
$runtimeDir = Join-Path $installDir 'runtime'
$jarPath = Join-Path $installDir 'solarminer-pc-agent-standalone.jar'
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

function Write-StartupSection([string]$title) {
    Write-Host ''
    Write-Host "[$title]"
}

function Write-StartupItem([string]$text) {
    Write-Host "  - $text"
}

function Write-StartupAction([string]$text) {
    Write-Host "  ACTION REQUIRED: $text" -ForegroundColor Yellow
}

function Test-DefenderExclusion([string]$path) {
    try {
        $preferences = Get-MpPreference -ErrorAction Stop
        return [bool](@($preferences.ExclusionPath) | Where-Object { $_ -and $_.TrimEnd('\') -ieq $path.TrimEnd('\') })
    } catch {
        Write-StartupItem "Defender status unavailable: $($_.Exception.Message)"
        return $false
    }
}

function Invoke-FirstStartBootstrap {
    $marker = Join-Path $installDir 'bootstrap-reviewed.txt'
    $firstReview = -not (Test-Path -LiteralPath $marker)
    $runReason = if ($Bootstrap) { 'manual request' } else { 'automatic start' }
    Write-Host ''
    Write-Host '=================================================='
    Write-Host 'SolarMiner PC-Agent - Startup checks'
    Write-Host "Reason: $runReason"
    Write-Host '=================================================='
    Write-StartupSection '1/3 Install directory'
    $probe = Join-Path $installDir ('write-probe-' + [guid]::NewGuid().ToString('N'))
    try {
        [System.IO.File]::WriteAllText($probe, 'ok')
        Write-StartupItem 'Install folder is writable [OK]'
    } finally { Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $probe }
    Write-StartupSection '2/3 GPU power-control readiness'
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    $administrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    Write-StartupItem "Administrator token for GPU power limits: $administrator"
    if (-not $administrator) {
        Write-StartupAction 'Administrator rights are required. Close this window, then start the launcher with Run as administrator.'
        throw 'SolarMiner PC-Agent requires Administrator rights to start.'
    }
    $nvidia = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    $nvidiaStatus = if ($nvidia) { 'available' } else { 'not found; normal without an NVIDIA GPU' }
    Write-StartupItem "NVIDIA driver tool: $nvidiaStatus"
    Write-StartupItem 'AMD Windows power-limit writes require a supported ADLX helper.'
    Write-StartupSection '3/3 Windows Defender'
    $defender = Get-Command Get-MpPreference -ErrorAction SilentlyContinue
    if ($defender) {
        if (Test-DefenderExclusion $installDir) {
            Write-StartupItem 'Defender exclusion verified for this install folder [OK]'
        } else {
            Write-StartupAction "Defender exclusion is missing for: $installDir"
            Write-StartupItem 'An exclusion reduces scanning of all files here, including miners.'
            $choice = Read-Host 'Add this folder as a Defender exclusion with UAC? [y/N]'
            if ($choice -match '^(y|yes)$') {
                try {
                    $escaped = $installDir.Replace("'", "''")
                    $command = "`$ErrorActionPreference='Stop'; Add-MpPreference -ExclusionPath '$escaped'"
                    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
                    $arguments = '-NoProfile -NonInteractive -EncodedCommand ' + $encoded
                    $process = Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait -PassThru
                    if ($process.ExitCode -ne 0 -or -not (Test-DefenderExclusion $installDir)) {
                        Write-Warning 'Defender did not confirm the exclusion. Review Windows Security policies and Protection history.'
                    } else { Write-StartupItem 'Defender exclusion verified [OK]' }
                } catch { Write-Warning "Defender approval failed or was declined: $($_.Exception.Message)" }
            }
        }
    } else { Write-StartupAction 'Windows Defender cmdlets are unavailable. Check security software manually.' }
    [System.IO.File]::WriteAllText($marker, [DateTime]::UtcNow.ToString('o'))
    Write-Host ''
    Write-Host '--------------------------------------------------'
    if ($firstReview) {
        Write-Host 'Startup checks complete. They run automatically on every start.'
    } else { Write-Host 'Startup checks complete.' }
    Write-Host '--------------------------------------------------'
}

Invoke-FirstStartBootstrap

Write-Host 'Checking for the latest SolarMiner PC-Agent release...'
$headers = @{ 'User-Agent' = 'SolarMiner-PC-Agent-Launcher' }
$release = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases/latest'
if ($release.draft -or $release.prerelease) { throw 'GitHub returned a draft or prerelease as latest.' }
$jarAsset = $release.assets | Where-Object name -eq 'solarminer-pc-agent-standalone.jar' | Select-Object -First 1
$hashAsset = $release.assets | Where-Object name -eq 'solarminer-pc-agent-standalone.jar.sha256' | Select-Object -First 1
if (-not $jarAsset -or -not $hashAsset) { throw 'The latest release is missing the Agent JAR or its SHA-256 file.' }

$tempHash = Join-Path $installDir 'solarminer-pc-agent-standalone.jar.sha256.download'
try {
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $hashAsset.browser_download_url -OutFile $tempHash
    $hashText = (Get-Content -LiteralPath $tempHash -Raw).Trim()
    $hashMatch = [regex]::Match($hashText, '^([0-9a-fA-F]{64})\s+\*?solarminer-pc-agent-standalone\.jar$')
    if (-not $hashMatch.Success) {
        throw 'The PC-Agent SHA-256 file has an invalid format.'
    }
    $expectedHash = $hashMatch.Groups[1].Value.ToLowerInvariant()
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $tempHash
}
$currentHash = if (Test-Path -LiteralPath $jarPath) { (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant() } else { '' }
if ($currentHash -ne $expectedHash) {
    Write-Host "Downloading PC-Agent $($release.tag_name)..."
    $tempJar = "$jarPath.download"
    try {
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $jarAsset.browser_download_url -OutFile $tempJar
        $downloadHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $tempJar).Hash.ToLowerInvariant()
        if ($downloadHash -ne $expectedHash) { throw 'PC-Agent JAR SHA-256 validation failed.' }
        Move-Item -Force -LiteralPath $tempJar -Destination $jarPath
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $tempJar
    }
} else {
    Write-Host "PC-Agent $($release.tag_name) is already downloaded."
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

Write-Host 'Starting SolarMiner PC-Agent. Open http://127.0.0.1:8084/ in your browser.'
Push-Location $installDir
try {
    & $javaExe -jar $jarPath '--solarminer.agent.standalone=true' '--server.address=0.0.0.0'
    if ($LASTEXITCODE -ne 0) { throw "PC-Agent exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
}
