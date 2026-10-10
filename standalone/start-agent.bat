@echo off
setlocal
set "INSTALL_DIR=%LOCALAPPDATA%\SolarMiner\PC-Agent"
set "PS_SCRIPT=%INSTALL_DIR%\start-agent.ps1"

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $h=@{'User-Agent'='SolarMiner-PC-Agent-Launcher'}; $r=Invoke-RestMethod -Headers $h -Uri 'https://api.github.com/repos/Solarminer-app/pc-agent/releases/latest'; $a=$r.assets | Where-Object name -eq 'start-agent.ps1' | Select-Object -First 1; if(-not $a){throw 'The latest PC-Agent release has no launcher asset.'}; $tmp='%PS_SCRIPT%.download'; try { Invoke-WebRequest -UseBasicParsing -Headers $h -Uri $a.browser_download_url -OutFile $tmp; Move-Item -Force -LiteralPath $tmp -Destination '%PS_SCRIPT%' } finally { Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $tmp }"

if errorlevel 1 (
  if not exist "%PS_SCRIPT%" (
    echo SolarMiner PC-Agent launcher could not be downloaded and no saved launcher is available.
    pause
    exit /b 1
  )
  echo The launcher update could not be downloaded. Starting the saved launcher.
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"

if errorlevel 1 (
  echo.
  echo SolarMiner PC-Agent could not be downloaded or started. Check your internet connection and try again.
  pause
  exit /b 1
)
