@echo off
setlocal
set "INSTALL_DIR=%LOCALAPPDATA%\SolarMiner\PC-Agent-Beta"
set "PS_SCRIPT=%INSTALL_DIR%\start-agent-beta.ps1"

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

if not exist "%PS_SCRIPT%" (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $h=@{'User-Agent'='SolarMiner-PC-Agent-Beta-Launcher'}; $rs=Invoke-RestMethod -Headers $h -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases?per_page=100'; $r=$rs | Where-Object { $_.prerelease -and $_.tag_name -like 'pc-agent-beta-*' } | Sort-Object published_at -Descending | Select-Object -First 1; if(-not $r){throw 'No SolarMiner PC-Agent beta release is available.'}; $a=$r.assets | Where-Object name -eq 'start-agent-beta.ps1' | Select-Object -First 1; if(-not $a){throw 'The latest PC-Agent beta release has no beta launcher asset.'}; Invoke-WebRequest -UseBasicParsing -Headers $h -Uri $a.browser_download_url -OutFile '%PS_SCRIPT%'"
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"

if errorlevel 1 (
  echo.
  echo SolarMiner PC-Agent beta could not be downloaded or started. Check your internet connection and try again.
  pause
  exit /b 1
)
