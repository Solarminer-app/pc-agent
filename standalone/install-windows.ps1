param([ValidateSet('stable', 'beta')][string]$Channel = 'stable')
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$headers = @{ 'User-Agent' = 'SolarMiner-PC-Agent-Installer' }
if ($Channel -eq 'beta') {
    $installDir = Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent-Beta'
    $launcher = Join-Path $installDir 'start-agent-beta.bat'
    $releases = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases?per_page=100'
    $release = $releases | Where-Object { $_.prerelease -and $_.tag_name -like 'pc-agent-beta-*' } | Sort-Object published_at -Descending | Select-Object -First 1
    if (-not $release) { throw 'Kein PC-Agent-Beta-Release gefunden. / No PC Agent beta release found.' }
    $asset = $release.assets | Where-Object name -eq 'start-agent-beta.bat' | Select-Object -First 1
    if (-not $asset) { throw 'Beta-Launcher fehlt. / Beta launcher is missing.' }
    $launcherUrl = $asset.browser_download_url
} else {
    $installDir = Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent'
    $launcher = Join-Path $installDir 'start-agent.bat'
    $launcherUrl = 'https://github.com/Solarminer-app/pc-agent/releases/latest/download/start-agent.bat'
}
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

try {
    Invoke-WebRequest -UseBasicParsing `
        -Headers $headers -Uri $launcherUrl `
        -OutFile "$launcher.download"
    Move-Item -Force -LiteralPath "$launcher.download" -Destination $launcher
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath "$launcher.download"
}

Start-Process -FilePath $launcher -Verb RunAs
