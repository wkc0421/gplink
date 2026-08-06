@echo off
setlocal

cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js was not found in PATH.
    exit /b 1
)

where pnpm.cmd >nul 2>nul
if errorlevel 1 (
    echo [ERROR] pnpm was not found in PATH.
    exit /b 1
)

if not exist "node_modules\" (
    echo [INFO] Installing frontend dependencies...
    call pnpm.cmd install --frozen-lockfile
    if errorlevel 1 goto :failed
)

echo [INFO] Updating module TypeScript paths...
call pnpm.cmd update:tsconfig
if errorlevel 1 goto :failed

echo [INFO] Building frontend package...
call pnpm.cmd build
if errorlevel 1 goto :failed

if not exist "dist\index.html" (
    echo [ERROR] Build completed without dist\index.html.
    exit /b 1
)

echo [SUCCESS] Frontend package: %CD%\dist
exit /b 0

:failed
echo [ERROR] Frontend packaging failed.
exit /b 1
