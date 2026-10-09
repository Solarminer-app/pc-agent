param([switch]$Bootstrap)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$installDir = Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent-Beta'
$runtimeDir = Join-Path $installDir 'runtime'
$jarPath = Join-Path $installDir 'solarminer-pc-agent-standalone.jar'
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

function Test-DefenderExclusion([string]$path) {
    try {
        $preferences = Get-MpPreference -ErrorAction Stop
        return [bool](@($preferences.ExclusionPath) | Where-Object { $_ -and $_.TrimEnd('\') -ieq $path.TrimEnd('\') })
    } catch {
        Write-Host "Defender status unavailable / Defender-Status nicht verfügbar: $($_.Exception.Message)"
        return $false
    }
}

function Invoke-FirstStartBootstrap {
    $marker = Join-Path $installDir 'bootstrap-reviewed.txt'
    $firstReview = -not (Test-Path -LiteralPath $marker)
    $runReason = if ($Bootstrap) { 'manual request / manuelle Anforderung' } else { 'automatic start / automatischer Start' }
    Write-Host "`nSolarMiner beta startup checks / SolarMiner Beta-Startprüfung ($runReason)"
    $probe = Join-Path $installDir ('write-probe-' + [guid]::NewGuid().ToString('N'))
    try {
        [System.IO.File]::WriteAllText($probe, 'ok')
        Write-Host 'Install folder writable / Installationsordner beschreibbar'
    } finally { Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $probe }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    $administrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    Write-Host "Administrator token for GPU power limits / Administratorrechte für GPU-Leistungsgrenzen: $administrator"
    $nvidia = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    Write-Host "NVIDIA driver tool / NVIDIA-Treiberwerkzeug: $(if ($nvidia) { 'available / verfügbar' } else { 'not found / nicht vorhanden; normal ohne NVIDIA-GPU' })"
    Write-Host 'AMD Windows power-limit writes require a supported ADLX helper / AMD-Leistungsgrenzen unter Windows benötigen einen unterstützten ADLX-Helfer.'
    $defender = Get-Command Get-MpPreference -ErrorAction SilentlyContinue
    if ($defender) {
        if (Test-DefenderExclusion $installDir) {
            Write-Host 'Defender exclusion verified / Defender-Ausnahme für diesen Installationsordner bestätigt.'
        } else {
            Write-Host "Defender exclusion missing / Defender-Ausnahme fehlt für: $installDir"
            Write-Host 'An exclusion reduces scanning of all files here, including miners / Eine Ausnahme verringert die Prüfung aller Dateien hier, einschließlich Miner.'
            $choice = Read-Host 'Add this folder as a Defender exclusion with UAC? / Ordner per UAC als Defender-Ausnahme hinzufügen? [y/N]'
            if ($choice -match '^(y|yes|j|ja)$') {
                try {
                    $escaped = $installDir.Replace("'", "''")
                    $command = "`$ErrorActionPreference='Stop'; Add-MpPreference -ExclusionPath '$escaped'"
                    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
                    $arguments = '-NoProfile -NonInteractive -EncodedCommand ' + $encoded
                    $process = Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait -PassThru
                    if ($process.ExitCode -ne 0 -or -not (Test-DefenderExclusion $installDir)) {
                        Write-Warning 'Defender did not confirm the exclusion / Defender hat die Ausnahme nicht bestätigt. Review Windows Security policies and Protection history / Windows-Sicherheitsrichtlinien und Schutzverlauf prüfen.'
                    } else { Write-Host 'Defender exclusion verified / Defender-Ausnahme bestätigt.' }
                } catch { Write-Warning "Defender approval failed or was declined / Defender-Freigabe fehlgeschlagen oder abgelehnt: $($_.Exception.Message)" }
            }
        }
    } else { Write-Host 'Windows Defender cmdlets unavailable / Windows-Defender-Befehle nicht verfügbar. Check security software manually / Sicherheitssoftware manuell prüfen.' }
    [System.IO.File]::WriteAllText($marker, [DateTime]::UtcNow.ToString('o'))
    if ($firstReview) {
        Write-Host 'Initial beta startup checks complete / Erste Beta-Startprüfung abgeschlossen. Checks run automatically on every start / Prüfungen laufen bei jedem Start automatisch.'
    } else {
        Write-Host 'Beta startup checks complete / Beta-Startprüfung abgeschlossen.'
    }
}

Invoke-FirstStartBootstrap

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
