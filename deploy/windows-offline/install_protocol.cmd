@echo off
setlocal
cd /d "%~dp0"
title GPLink2 Modbus protocol import

echo Importing the packaged Modbus protocol through the local GPLink2 API...
echo.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Install-Protocol.ps1" -ConfigPath "%~dp0config\deploy-config.psd1"
set "GPLINK_EXIT=%ERRORLEVEL%"
echo.
if "%GPLINK_EXIT%"=="0" (
    echo [SUCCESS] Modbus protocol import completed.
) else (
    echo [FAILED] Modbus protocol import failed. Review logs\install-protocol-*.log and install-protocol-report.*.
)
if not "%GPLINK_NO_PAUSE%"=="1" pause
exit /b %GPLINK_EXIT%
