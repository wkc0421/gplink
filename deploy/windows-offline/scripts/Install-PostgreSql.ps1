[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$context = Get-DeploymentContext -ConfigPath $ConfigPath
$config = $context.Config
if (-not [string]::IsNullOrWhiteSpace($env:GPLINK_DEPLOY_LOG_PATH)) { Initialize-DeployLog -LogPath $env:GPLINK_DEPLOY_LOG_PATH -Config $config }
$pgPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'postgresql'
$installer = Join-Path $context.PackageDirectory ([string]$pgPackage.file)
$pgConfig = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_config.exe'
$psql = Join-Path ([string]$config.PgInstallRoot) 'bin\psql.exe'
$pgIsReady = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_isready.exe'
$pgCtl = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_ctl.exe'
$expectedPgVersion = ([string]$pgPackage.version -replace '-\d+$', '')

if ((Test-Path -LiteralPath $pgConfig -PathType Leaf) -and (Test-Path -LiteralPath $psql -PathType Leaf)) {
    $version = (& $pgConfig '--version' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $version -notmatch ('^PostgreSQL\s+{0}(?:\s|$)' -f [regex]::Escape($expectedPgVersion))) {
        throw "Existing PostgreSQL installation is '$version', expected PostgreSQL $expectedPgVersion."
    }
    if ($null -eq (Get-ServiceOrNull -Name ([string]$config.PgServiceName))) {
        $clusterVersionPath = Join-Path ([string]$config.PgData) 'PG_VERSION'
        $controlPath = Join-Path ([string]$config.PgData) 'global\pg_control'
        if (-not (Test-Path -LiteralPath $pgCtl -PathType Leaf) -or -not (Test-Path -LiteralPath $clusterVersionPath -PathType Leaf) -or -not (Test-Path -LiteralPath $controlPath -PathType Leaf)) {
            throw 'PostgreSQL files exist but the service is missing and PgData is not a complete recoverable cluster. Manual recovery is required.'
        }
        $clusterMajor = (Get-Content -LiteralPath $clusterVersionPath -Raw).Trim()
        if ($clusterMajor -ne '16') { throw "Existing PostgreSQL data cluster major version is '$clusterMajor', expected 16." }
        [void](Invoke-NativeProcess -FilePath $pgCtl -ArgumentList @('register', '-N', [string]$config.PgServiceName, '-D', [string]$config.PgData, '-S', 'auto', '-U', [string]$config.PostgresServiceAccount, '-P', [string]$config.PostgresServicePassword) -Description 'Registering the recoverable PostgreSQL data cluster as a Windows service')
        if ($null -eq (Get-ServiceOrNull -Name ([string]$config.PgServiceName))) { throw 'pg_ctl did not recreate the configured PostgreSQL service.' }
        Write-DeployLog -Message 'Recovered a complete PostgreSQL data cluster whose Windows service registration was missing.'
    }
    $existingService = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ([string]$config.PgServiceName).Replace("'", "''"))
    $serviceExecutable = Get-WindowsServiceExecutablePath -PathName ([string]$existingService.PathName)
    $serviceDataDirectory = Get-WindowsCommandLineArgumentValue -CommandLine ([string]$existingService.PathName) -ArgumentName '-D'
    if (-not [string]::Equals($serviceExecutable, [System.IO.Path]::GetFullPath($pgCtl), [System.StringComparison]::OrdinalIgnoreCase) -or
        [string]::IsNullOrWhiteSpace($serviceDataDirectory) -or
        -not [string]::Equals([System.IO.Path]::GetFullPath($serviceDataDirectory), [System.IO.Path]::GetFullPath([string]$config.PgData), [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-WindowsAccountMatches -Expected ([string]$config.PostgresServiceAccount) -Actual ([string]$existingService.StartName))) {
        throw 'Existing PostgreSQL service executable, data directory, or service account does not match the managed configuration.'
    }
    Write-DeployLog -Message "Matching PostgreSQL installation found ($version); keeping its data directory."
} else {
    if (Test-Path -LiteralPath ([string]$config.PgData)) {
        $existingData = Get-ChildItem -LiteralPath ([string]$config.PgData) -Force -ErrorAction Stop | Select-Object -First 1
        if ($null -ne $existingData) { throw 'PostgreSQL data directory is non-empty but pg_config.exe is missing. Manual recovery is required.' }
    }
    $installerDirectory = Join-Path $env:ProgramData 'GpLink2\installer'
    New-Item -ItemType Directory -Path $installerDirectory -Force | Out-Null
    Set-RestrictedAcl -Path $installerDirectory -Directory
    $optionFile = Join-Path $installerDirectory ('postgresql-' + [guid]::NewGuid().ToString('N') + '.conf')
    $optionLines = @(
        'mode=unattended',
        'unattendedmodeui=none',
        ('prefix=' + [string]$config.PgInstallRoot),
        ('datadir=' + [string]$config.PgData),
        ('serverport=' + [string]$config.PgPort),
        ('servicename=' + [string]$config.PgServiceName),
        ('serviceaccount=' + [string]$config.PostgresServiceAccount),
        ('servicepassword=' + [string]$config.PostgresServicePassword),
        ('superaccount=' + [string]$config.PostgresSuperUser),
        ('superpassword=' + [string]$config.PostgresSuperPassword),
        'enable-components=server,commandlinetools',
        'disable-components=pgAdmin,stackbuilder',
        'create_shortcuts=0'
    )
    try {
        Write-Utf8NoBomFile -Path $optionFile -Content (($optionLines -join [Environment]::NewLine) + [Environment]::NewLine)
        Set-RestrictedAcl -Path $optionFile
        [void](Invoke-NativeProcess -FilePath $installer -ArgumentList @('--optionfile', $optionFile) -Description 'Installing PostgreSQL 16 in unattended mode')
    } finally {
        if (Test-Path -LiteralPath $optionFile) { Remove-Item -LiteralPath $optionFile -Force }
    }
}

foreach ($required in @($pgConfig, $psql, $pgIsReady, $pgCtl)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "PostgreSQL installation is incomplete: $required" }
}
if ($null -eq (Get-ServiceOrNull -Name ([string]$config.PgServiceName))) { throw 'PostgreSQL installer did not create the configured service.' }
Set-Service -Name ([string]$config.PgServiceName) -StartupType Automatic
if ((Get-Service -Name ([string]$config.PgServiceName)).Status -ne 'Running') { Start-Service -Name ([string]$config.PgServiceName) }
Wait-ServiceRunning -Name ([string]$config.PgServiceName) -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)

$readyOutput = & $pgIsReady '-h' '127.0.0.1' '-p' ([string]$config.PgPort) 2>&1
if ($LASTEXITCODE -ne 0) { throw ('PostgreSQL is not accepting local connections: ' + ($readyOutput | Out-String).Trim()) }
$actualServerVersion = ((Invoke-Psql -PsqlPath $psql -Password ([string]$config.PostgresSuperPassword) -User ([string]$config.PostgresSuperUser) -Database 'postgres' -Port ([int]$config.PgPort) -Sql 'SHOW server_version;') -join '').Trim()
if ($actualServerVersion -ne $expectedPgVersion) { throw "Running PostgreSQL server version is '$actualServerVersion', expected '$expectedPgVersion'." }

[pscustomobject]@{
    PgConfig = $pgConfig
    Psql = $psql
    PgIsReady = $pgIsReady
    InstallRoot = [string]$config.PgInstallRoot
    DataDirectory = [string]$config.PgData
    ServiceName = [string]$config.PgServiceName
    Version = $actualServerVersion
}
