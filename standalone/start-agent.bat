@echo off
setlocal
set "INSTALL_DIR=%LOCALAPPDATA%\SolarMiner\PC-Agent"
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if exist "%~dp0start-agent.ps1" (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-agent.ps1"
) else (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $h=@{'User-Agent'='SolarMiner-PC-Agent-Launcher'}; $r=Invoke-RestMethod -Headers $h -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases/latest'; $a=$r.assets | Where-Object name -eq 'start-agent.ps1' | Select-Object -First 1; if(-not $a){throw 'The latest PC-Agent release has no launcher asset.'}; $p=Join-Path (Join-Path $env:LOCALAPPDATA 'SolarMiner\PC-Agent') 'start-agent.ps1'; Invoke-WebRequest -UseBasicParsing -Headers $h -Uri $a.browser_download_url -OutFile $p; & $p"
)
if errorlevel 1 (
  echo.
  echo SolarMiner PC-Agent could not be downloaded or started. Check your internet connection and try again.
  pause
  exit /b 1
)
