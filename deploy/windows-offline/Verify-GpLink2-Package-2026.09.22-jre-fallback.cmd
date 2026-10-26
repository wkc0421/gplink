@echo off
setlocal
cd /d "%~dp0"
title GPLink2 Offline Package Verification

set "GPLINK_ZIP=GpLink2-Offline-gplink2-windows-offline-2026.09.22-network-only-modbus-import-jdk-priority.zip"
set "GPLINK_EXPECTED=895DD63DCEA7FC388CB69F27ABC920A019EA254F8DC739DBFEC91E83C94A5CB0"

echo Verifying the GPLink2 offline package. Please wait...
echo.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; try { $zipPath=Join-Path (Get-Location) $env:GPLINK_ZIP; $sidecarPath=$zipPath+'.sha256'; if(-not (Test-Path -LiteralPath $zipPath -PathType Leaf)){throw ('ZIP file not found: '+$env:GPLINK_ZIP)}; if(-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)){throw ('SHA-256 sidecar not found: '+$env:GPLINK_ZIP+'.sha256')}; $match=[regex]::Match([IO.File]::ReadAllText($sidecarPath),'(?i)\b[0-9a-f]{64}\b'); if(-not $match.Success){throw 'The SHA-256 sidecar format is invalid.'}; $sidecarHash=$match.Value.ToUpperInvariant(); if($sidecarHash -ne $env:GPLINK_EXPECTED){throw 'The SHA-256 sidecar does not belong to this release.'}; $actual=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToUpperInvariant(); if($actual -ne $env:GPLINK_EXPECTED){throw ('ZIP hash mismatch. Actual SHA-256: '+$actual)}; Write-Host '[PASS] ZIP integrity and SHA-256 verification succeeded.' -ForegroundColor Green; Write-Host ('File: '+$env:GPLINK_ZIP); Write-Host ('SHA-256: '+$actual); exit 0 } catch { Write-Host ('[FAIL] '+$_.Exception.Message) -ForegroundColor Red; Write-Host 'Copy the ZIP, sidecar, and verifier again before installing.'; exit 1 }"
set "GPLINK_EXIT=%ERRORLEVEL%"
echo.
if "%GPLINK_EXIT%"=="0" (
    echo You may extract the ZIP and run Check-GpLink.cmd.
) else (
    echo Do not extract or install this ZIP.
)
echo.
if not "%GPLINK_NO_PAUSE%"=="1" pause
exit /b %GPLINK_EXIT%
