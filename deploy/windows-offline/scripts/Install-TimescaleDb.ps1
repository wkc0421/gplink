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
$package = Get-ManifestPackage -Manifest $context.Manifest -Id 'timescaledb'
$archivePath = Join-Path $context.PackageDirectory ([string]$package.file)
$pgConfig = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_config.exe'
$psql = Join-Path ([string]$config.PgInstallRoot) 'bin\psql.exe'
$createdb = Join-Path ([string]$config.PgInstallRoot) 'bin\createdb.exe'
if (-not (Test-Path -LiteralPath $pgConfig)) { throw 'pg_config.exe was not found.' }

$pgVersion = (& $pgConfig '--version' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $pgVersion -notmatch ('^PostgreSQL {0}\.' -f [int]$package.postgresMajor)) {
    throw "TimescaleDB package does not match installed PostgreSQL: $pgVersion"
}
$pkglibdir = (& $pgConfig '--pkglibdir' 2>&1 | Out-String).Trim()
$sharedir = (& $pgConfig '--sharedir' 2>&1 | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($pkglibdir) -or [string]::IsNullOrWhiteSpace($sharedir)) { throw 'pg_config did not return library and share directories.' }
$extensionDirectory = Join-Path $sharedir 'extension'

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('gplink-timescale-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $temp)
    $dlls = @(Get-ChildItem -LiteralPath $temp -Recurse -File | Where-Object { $_.Name -like 'timescaledb*.dll' })
    $extensionFiles = @(Get-ChildItem -LiteralPath $temp -Recurse -File | Where-Object { $_.Name -eq 'timescaledb.control' -or $_.Name -like 'timescaledb--*.sql' })
    if ($dlls.Count -eq 0 -or $extensionFiles.Count -eq 0) { throw 'TimescaleDB archive content is incomplete.' }

    $service = Get-Service -Name ([string]$config.PgServiceName) -ErrorAction Stop
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name ([string]$config.PgServiceName) -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds([int]$config.ServiceStartTimeoutSeconds))
    }
    $openSslBin = Join-Path ([string]$config.OpenSslInstallRoot) 'bin'
    $pgBin = Join-Path ([string]$config.PgInstallRoot) 'bin'
    foreach ($dllName in @('libcrypto-3-x64.dll', 'libssl-3-x64.dll')) {
        $sourceDll = Join-Path $openSslBin $dllName
        if (-not (Test-Path -LiteralPath $sourceDll -PathType Leaf)) { throw "OpenSSL runtime DLL not found after installation: $sourceDll" }
        Copy-Item -LiteralPath $sourceDll -Destination (Join-Path $pgBin $dllName) -Force
    }
    New-Item -ItemType Directory -Path $pkglibdir -Force | Out-Null
    New-Item -ItemType Directory -Path $extensionDirectory -Force | Out-Null
    foreach ($file in $dlls) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $pkglibdir $file.Name) -Force }
    foreach ($file in $extensionFiles) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $extensionDirectory $file.Name) -Force }

    $postgresqlConf = Join-Path ([string]$config.PgData) 'postgresql.conf'
    if (-not (Test-Path -LiteralPath $postgresqlConf)) { throw "PostgreSQL configuration not found: $postgresqlConf" }
    $content = Get-Content -LiteralPath $postgresqlConf -Raw
    $beginMarker = '# BEGIN GPLINK2 MANAGED SETTINGS'
    $endMarker = '# END GPLINK2 MANAGED SETTINGS'
    $managed = @(
        $beginMarker,
        "listen_addresses = '127.0.0.1'",
        "shared_preload_libraries = 'timescaledb'",
        $endMarker
    ) -join [Environment]::NewLine
    $pattern = '(?ms)^' + [regex]::Escape($beginMarker) + '.*?^' + [regex]::Escape($endMarker) + '\s*'
    if ($content -match $pattern) { $content = [regex]::Replace($content, $pattern, '') }
    $content = $content.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $managed + [Environment]::NewLine
    Write-Utf8NoBomFile -Path $postgresqlConf -Content $content
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    $postgresService = Get-Service -Name ([string]$config.PgServiceName) -ErrorAction SilentlyContinue
    if ($null -ne $postgresService -and $postgresService.Status -eq 'Stopped') {
        try { Start-Service -Name ([string]$config.PgServiceName) }
        catch { Write-DeployLog -Level 'WARN' -Message 'PostgreSQL could not be restarted during TimescaleDB cleanup.' }
    }
}

Wait-ServiceRunning -Name ([string]$config.PgServiceName) -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)

$superUser = [string]$config.PostgresSuperUser
$superPassword = [string]$config.PostgresSuperPassword
$port = [int]$config.PgPort
$roleId = Quote-PgIdentifier -Value ([string]$config.DatabaseUser)
$roleLiteral = Quote-PgLiteral -Value ([string]$config.DatabaseUser)
$passwordLiteral = Quote-PgLiteral -Value ([string]$config.DatabasePassword)
$databaseId = Quote-PgIdentifier -Value ([string]$config.DatabaseName)
$databaseLiteral = Quote-PgLiteral -Value ([string]$config.DatabaseName)
$roleExists = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql "SELECT 1 FROM pg_roles WHERE rolname = $roleLiteral;") -join '').Trim()
if ($roleExists -eq '1') {
    $roleSql = "ALTER ROLE $roleId WITH LOGIN PASSWORD $passwordLiteral NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;"
} else {
    $roleSql = "CREATE ROLE $roleId LOGIN PASSWORD $passwordLiteral NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;"
}
[void](Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql $roleSql)
$databaseExists = (Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql "SELECT 1 FROM pg_database WHERE datname = $databaseLiteral;") -join ''
if ($databaseExists.Trim() -ne '1') {
    $oldPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $superPassword
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $createdbOutput = & $createdb '-h' '127.0.0.1' '-p' ([string]$port) '-U' $superUser '-O' ([string]$config.DatabaseUser) ([string]$config.DatabaseName) 2>&1
            $createdbExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        if ($createdbExitCode -ne 0) { throw ('createdb failed: ' + ($createdbOutput | Out-String).Trim()) }
    } finally { $env:PGPASSWORD = $oldPassword }
}
[void](Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql "ALTER DATABASE $databaseId OWNER TO $roleId;")
$schemaId = Quote-PgIdentifier -Value ([string]$config.DatabaseSchema)
[void](Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database ([string]$config.DatabaseName) -Port $port -Sql "CREATE EXTENSION IF NOT EXISTS timescaledb; CREATE SCHEMA IF NOT EXISTS $schemaId AUTHORIZATION $roleId; GRANT ALL ON SCHEMA $schemaId TO $roleId;")

$memory = Get-PhysicalMemoryGB
$profile = if ($memory -ge 32) { '32g' } else { '16g' }
$tuningFile = Join-Path $PSScriptRoot ("sql\pgsql-$profile.sql")
[void](Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database ([string]$config.DatabaseName) -Port $port -File $tuningFile)
Restart-Service -Name ([string]$config.PgServiceName) -Force
Wait-ServiceRunning -Name ([string]$config.PgServiceName) -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)

$actualVersion = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database ([string]$config.DatabaseName) -Port $port -Sql "SELECT extversion FROM pg_extension WHERE extname='timescaledb';") -join '').Trim()
if ($actualVersion -ne [string]$package.version) { throw "TimescaleDB extension version is '$actualVersion', expected '$($package.version)'." }
Write-DeployLog -Message "TimescaleDB $actualVersion is installed and PostgreSQL tuning profile $profile is active."

[pscustomobject]@{ TimescaleVersion = $actualVersion; TuningProfile = $profile; LibraryDirectory = $pkglibdir; ExtensionDirectory = $extensionDirectory }
