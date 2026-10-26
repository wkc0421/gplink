@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

powershell.exe -NoProfile -Command "if ((New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
    set "GPLINK_ENTRY=%~f0"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$process = Start-Process -FilePath $env:GPLINK_ENTRY -Verb RunAs -Wait -PassThru; exit $process.ExitCode"
    exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Install.ps1" -ConfigPath "%~dp0config\deploy-config.psd1"
set "GPLINK_EXIT=%ERRORLEVEL%"
echo.
if "%GPLINK_EXIT%"=="0" (
    echo [SUCCESS] GPLink2 installation completed successfully.
) else (
    echo [FAILED] GPLink2 installation failed. Review the report under logs.
)
pause
exit /b %GPLINK_EXIT%
