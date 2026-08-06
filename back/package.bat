@echo off
setlocal

set "PROJECT_DIR=%~dp0jetlinks-community-2.10\jetlinks-community-2.10"
if not exist "%PROJECT_DIR%\mvnw.cmd" (
    echo [ERROR] Maven wrapper was not found: %PROJECT_DIR%\mvnw.cmd
    exit /b 1
)

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ready

set "JAVA_HOME="
for /f "tokens=2 delims==" %%I in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.home ="') do set "JAVA_HOME=%%I"
for /f "tokens=*" %%I in ("%JAVA_HOME%") do set "JAVA_HOME=%%I"

:java_ready
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] A JDK was not found. Configure JAVA_HOME with JDK 17 or newer.
    exit /b 1
)

cd /d "%PROJECT_DIR%"

for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-Date -Format 'yyyy-MM-dd HH:mm:ss'"') do set "BUILD_TIMESTAMP=%%I"

echo [INFO] Using JAVA_HOME=%JAVA_HOME%
echo [INFO] Building backend package...
call mvnw.cmd clean package -Dmaven.test.skip=true "-Dmaven.build.timestamp=%BUILD_TIMESTAMP%"
if errorlevel 1 goto :failed

set "OUTPUT=%PROJECT_DIR%\jetlinks-standalone\target\application.jar"
if not exist "%OUTPUT%" (
    echo [ERROR] Build completed without application.jar.
    exit /b 1
)

echo [SUCCESS] Backend package: %OUTPUT%
exit /b 0

:failed
echo [ERROR] Backend packaging failed.
exit /b 1
