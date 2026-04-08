@echo off
setlocal

cd /d "%~dp0"

if "%MQTT_SIMULATOR_ADDR%"=="" set MQTT_SIMULATOR_ADDR=:8099
if "%MQTT_SIMULATOR_DATA_DIR%"=="" set MQTT_SIMULATOR_DATA_DIR=%cd%\mqtt-simulator-data

echo Starting MQTT Simulator...
echo   Address : %MQTT_SIMULATOR_ADDR%
echo   Data dir: %MQTT_SIMULATOR_DATA_DIR%

where go >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Go is not installed or not in PATH.
  exit /b 1
)

if not exist "%MQTT_SIMULATOR_DATA_DIR%" mkdir "%MQTT_SIMULATOR_DATA_DIR%"

go run .

endlocal
