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

title GPLink2 destructive reinstall
echo.
echo This entry removes the configured GPLink2 application and PostgreSQL data.
echo The offline package directory is preserved. Shared VC++ and OpenSSL runtimes are preserved.
echo The action is irreversible for the old application and database data.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Reinstall.ps1" -ConfigPath "%~dp0config\deploy-config.psd1"
set "GPLINK_EXIT=%ERRORLEVEL%"
echo.
if "%GPLINK_EXIT%"=="0" (
    echo [SUCCESS] GPLink2 reinstall completed successfully.
) else if "%GPLINK_EXIT%"=="2" (
    echo [CANCELLED] Reinstall was cancelled; no files or services were changed.
) else (
    echo [FAILED] GPLink2 reinstall failed. Review logs\reinstall-*.log.
)
pause
exit /b %GPLINK_EXIT%
