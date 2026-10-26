[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [switch]$Quiet,
    [switch]$Reinstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$bundleRoot = Get-BundleRoot
$logDirectory = Join-Path $bundleRoot 'logs'
$reportPath = Join-Path $logDirectory 'preflight-report.json'
$textReportPath = Join-Path $logDirectory 'preflight-report.txt'
$results = New-Object System.Collections.ArrayList
$context = $null
$config = $null
$configSchemaValid = $false
$configurationValid = $false

function Add-PreflightResult {
    param([string]$Id, [string]$Category, [bool]$Passed, [string]$Message, [string]$Remediation = '')
    [void]$results.Add((New-CheckResult -Id $Id -Category $Category -Passed $Passed -Message $Message -Remediation $Remediation))
    if (-not $Quiet) {
        $marker = if ($Passed) { '[PASS]' } else { '[FAIL]' }
        Write-Host "$marker $Category/$Id - $Message"
        if (-not $Passed -and -not [string]::IsNullOrWhiteSpace($Remediation)) { Write-Host "       Fix: $Remediation" }
    }
}

function Invoke-PreflightCheck {
    param([string]$Id, [string]$Category, [scriptblock]$Action, [string]$SuccessMessage, [string]$Remediation)
    try {
        $detail = & $Action
        $message = if ($null -ne $detail -and -not [string]::IsNullOrWhiteSpace([string]$detail)) { [string]$detail } else { $SuccessMessage }
        Add-PreflightResult -Id $Id -Category $Category -Passed $true -Message $message
    } catch {
        Add-PreflightResult -Id $Id -Category $Category -Passed $false -Message $_.Exception.Message -Remediation $Remediation
    }
}

try {
    $config = Read-DeployConfig -ConfigPath $ConfigPath
    Initialize-DeployLog -LogPath (Join-Path $logDirectory ('preflight-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))) -Config $config
    Add-PreflightResult -Id 'config-syntax' -Category 'configuration' -Passed $true -Message 'Configuration file is readable and syntactically valid.'
} catch {
    Initialize-DeployLog -LogPath (Join-Path $logDirectory ('preflight-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss')))
    Add-PreflightResult -Id 'config-syntax' -Category 'configuration' -Passed $false -Message $_.Exception.Message -Remediation 'Copy deploy-config.example.psd1 to deploy-config.psd1 and correct its PowerShell data syntax.'
}

if ($null -ne $config) {
    Invoke-PreflightCheck -Id 'config-schema' -Category 'configuration' -SuccessMessage 'Configuration keys, types, and value ranges are valid.' -Remediation 'Compare deploy-config.psd1 with deploy-config.example.psd1. Use unquoted numbers and $true/$false boolean values.' -Action {
        $validationErrors = @(Get-DeployConfigValidationErrors -Config $config)
        if ($validationErrors.Count -gt 0) {
            throw ("Configuration validation failed:`n - " + ($validationErrors -join "`n - "))
        }
        $script:configSchemaValid = $true
        "Configuration contains $($config.Keys.Count) recognized settings with valid types and ranges."
    }
}

if ($configSchemaValid) {
    Invoke-PreflightCheck -Id 'bundle-paths' -Category 'configuration' -SuccessMessage 'Package paths are contained in the bundle.' -Remediation 'Keep PackageDirectory and PackageManifest inside this offline bundle.' -Action {
        $script:context = Get-DeploymentContext -ConfigPath $ConfigPath
        'Package directory and manifest are contained in the offline bundle.'
    }
    Invoke-PreflightCheck -Id 'target-paths' -Category 'configuration' -SuccessMessage 'Target paths are absolute and safe.' -Remediation 'Use separate absolute non-root paths such as D:\GpLink2, D:\PostgreSQL16, D:\PostgreSQLData, and D:\OpenSSL-Win64.' -Action {
        foreach ($entry in @(@('AppRoot', $config.AppRoot), @('PgInstallRoot', $config.PgInstallRoot), @('PgData', $config.PgData), @('OpenSslInstallRoot', $config.OpenSslInstallRoot))) {
            [void](Assert-SafeAbsolutePath -Name $entry[0] -Path ([string]$entry[1]))
        }
        if ([System.IO.Path]::GetFullPath([string]$config.PgData) -eq [System.IO.Path]::GetFullPath([string]$config.PgInstallRoot)) {
            throw 'PgData and PgInstallRoot must be different directories.'
        }
        $appPrefix = [System.IO.Path]::GetFullPath([string]$config.AppRoot).TrimEnd('\') + '\'
        $dataFull = [System.IO.Path]::GetFullPath([string]$config.PgData).TrimEnd('\') + '\'
        if ($dataFull.StartsWith($appPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or $appPrefix.StartsWith($dataFull, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'PgData and AppRoot must be separate directory trees so application ACLs cannot affect PostgreSQL data.'
        }
    }
    Invoke-PreflightCheck -Id 'ports-and-hosts' -Category 'configuration' -SuccessMessage 'Ports and external address values are valid.' -Remediation 'Use ports from 1 through 65535 and non-empty host names.' -Action {
        foreach ($port in @([int]$config.ServerPort, [int]$config.PgPort, [int]$config.RedisPort, [int]$config.ExternalPort)) {
            if ($port -lt 1 -or $port -gt 65535) { throw "Invalid TCP port: $port" }
        }
        if ([int]$config.ServerPort -eq [int]$config.PgPort) { throw 'ServerPort and PgPort cannot be the same.' }
        foreach ($entry in @(@('ExternalHost', [string]$config.ExternalHost), @('RedisHost', [string]$config.RedisHost))) {
            if ([Uri]::CheckHostName($entry[1]) -eq [UriHostNameType]::Unknown) { throw "$($entry[0]) must be a host name or IP address without a URL scheme or path." }
        }
    }
    Invoke-PreflightCheck -Id 'identifiers' -Category 'configuration' -SuccessMessage 'Service and PostgreSQL identifiers are valid.' -Remediation 'Use simple alphanumeric, underscore, dot, or hyphen identifiers.' -Action {
        foreach ($entry in @(@('PgServiceName', $config.PgServiceName), @('AppServiceName', $config.AppServiceName), @('PostgresSuperUser', $config.PostgresSuperUser), @('DatabaseName', $config.DatabaseName), @('DatabaseUser', $config.DatabaseUser), @('DatabaseSchema', $config.DatabaseSchema))) {
            if ([string]$entry[1] -notmatch '^[A-Za-z0-9_.-]+$') { throw "$($entry[0]) contains unsupported characters." }
        }
        foreach ($key in @('PostgresSuperUser', 'DatabaseName', 'DatabaseUser', 'DatabaseSchema')) {
            if ([string]$config[$key] -notmatch '^[A-Za-z_][A-Za-z0-9_$-]{0,62}$') { throw "$key must be a valid PostgreSQL identifier of at most 63 ASCII characters." }
        }
        if ([string]$config.PostgresServiceAccount -notmatch '^[A-Za-z0-9_.\\-]+$') { throw 'PostgresServiceAccount contains unsupported characters.' }
        if ([string]$config.PgServiceName -eq [string]$config.AppServiceName) { throw 'PgServiceName and AppServiceName must be different.' }
        if ([string]$config.PostgresSuperUser -eq [string]$config.DatabaseUser) { throw 'DatabaseUser must be different from PostgresSuperUser so the application role is not a superuser.' }
        if ([string]$config.AdminUsername -match '[\x00-\x1F]' -or [string]$config.AdminUsername -match '^\s*$') { throw 'AdminUsername is invalid.' }
    }
    Invoke-PreflightCheck -Id 'secrets' -Category 'configuration' -SuccessMessage 'Passwords are populated and are not example placeholders.' -Remediation 'Replace every CHANGE_ME placeholder with a unique password of at least 6 characters.' -Action {
        foreach ($key in @('PostgresSuperPassword', 'PostgresServicePassword', 'DatabasePassword', 'AdminUserPassword')) {
            $value = [string]$config[$key]
            if (Test-PlaceholderSecret -Value $value) { throw "$key still contains an example placeholder." }
            if ($value.Length -lt 6) { throw "$key must contain at least 6 characters." }
            if ($value -match '[\x00-\x1F]') { throw "$key cannot contain control characters." }
        }
        if (-not [bool]$config.AllowPasswordlessRedis) {
            if (Test-PlaceholderSecret -Value ([string]$config.RedisPassword)) { throw 'RedisPassword still contains an example placeholder.' }
        }
    }
}

if ($configSchemaValid) {
    $configurationValid = @($results | Where-Object { $_.category -eq 'configuration' -and -not $_.passed }).Count -eq 0
}

if ($configurationValid -and $null -ne $context) {
    Invoke-PreflightCheck -Id 'manifest-shape' -Category 'packages' -SuccessMessage 'Package manifest has all required unique component IDs.' -Remediation 'Regenerate the bundle manifest with Prepare-Bundle.ps1.' -Action {
        $ids = @($context.Manifest.packages | ForEach-Object { [string]$_.id })
        foreach ($requiredId in @('application', 'protocol-modbus', 'java', 'postgresql', 'timescaledb', 'vcredist', 'openssl', 'nssm', 'baseline-sql')) {
            if (@($ids | Where-Object { $_ -eq $requiredId }).Count -ne 1) { throw "Package id '$requiredId' must appear exactly once." }
        }
        $fileNames = @($context.Manifest.packages | ForEach-Object { ([string]$_.file).ToLowerInvariant() })
        if (($fileNames | Select-Object -Unique).Count -ne $fileNames.Count) { throw 'Package filenames are not unique.' }
        foreach ($package in $context.Manifest.packages) {
            if ([string]$package.architecture -notin @('x64', 'any')) { throw "Unsupported architecture for $($package.id)." }
        }
    }
    foreach ($package in @($context.Manifest.packages)) {
        $packageCopy = $package
        Invoke-PreflightCheck -Id ('package-' + [string]$package.id) -Category 'packages' -SuccessMessage ("Package verified: {0}" -f $package.file) -Remediation 'Restore the exact file used to generate packages.json, or rebuild the bundle and update its manifest.' -Action {
            [void](Test-PackageFile -Package $packageCopy -PackageDirectory $context.PackageDirectory -RequireAuthenticode $true)
            "Verified $($packageCopy.file), version $($packageCopy.version)."
        }
    }
    Invoke-PreflightCheck -Id 'postgres-timescale-compatibility' -Category 'packages' -SuccessMessage 'PostgreSQL and TimescaleDB major versions match.' -Remediation 'Use the TimescaleDB Windows package compiled for PostgreSQL 16.' -Action {
        $pg = Get-ManifestPackage -Manifest $context.Manifest -Id 'postgresql'
        $ts = Get-ManifestPackage -Manifest $context.Manifest -Id 'timescaledb'
        if ([int]$pg.postgresMajor -ne [int]$ts.postgresMajor) { throw 'PostgreSQL and TimescaleDB package major versions differ.' }
        Test-TimescaleArchive -Path (Join-Path $context.PackageDirectory ([string]$ts.file)) -Version ([string]$ts.version) -PostgresMajor ([int]$ts.postgresMajor)
    }
    Invoke-PreflightCheck -Id 'jre-runtime' -Category 'packages' -SuccessMessage 'JRE archive expands and contains Windows x64 Java 17.' -Remediation 'Replace the JRE with the exact Temurin Windows x64 Java 17 archive in the manifest.' -Action {
        $java = Get-ManifestPackage -Manifest $context.Manifest -Id 'java'
        Test-JreArchive -Path (Join-Path $context.PackageDirectory ([string]$java.file)) -ExpectedVersion ([string]$java.version)
    }
    Invoke-PreflightCheck -Id 'java-runtime-selection' -Category 'environment' -SuccessMessage 'The system Java 17 JDK or bundled Java 17 runtime is available.' -Remediation 'Install a working x64 JDK 17 at D:\Program Files\Java\jdk-17, or restore the bundled JRE archive.' -Action {
        $systemJdk = Get-PreferredSystemJdk
        if ($systemJdk.Available) {
            "Detected x64 JDK $($systemJdk.Version) at $($systemJdk.JavaHome); installation will use it and skip bundled Java extraction."
        } else {
            "No usable JDK 17 found at D:\Program Files\Java\jdk-17; the verified bundled Java runtime will be installed. Details: $($systemJdk.Reason)"
        }
    }
    Invoke-PreflightCheck -Id 'application-jar' -Category 'packages' -SuccessMessage 'application.jar is a complete executable Spring Boot JAR.' -Remediation 'Supply the built jetlinks-standalone application.jar and regenerate packages.json.' -Action {
        $application = Get-ManifestPackage -Manifest $context.Manifest -Id 'application'
        Test-JarContent -Path (Join-Path $context.PackageDirectory ([string]$application.file))
    }
    Invoke-PreflightCheck -Id 'baseline-sql' -Category 'packages' -SuccessMessage 'The one-time GPLink2 baseline SQL is finalized for two network components and no protocol imports.' -Remediation 'Regenerate the network-only gplink-baseline.sql and rebuild the bundle manifest.' -Action {
        $baseline = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
        Test-BaselineSql -Path (Join-Path $context.PackageDirectory ([string]$baseline.file))
    }
    Invoke-PreflightCheck -Id 'protocol-modbus-jar' -Category 'packages' -SuccessMessage 'Optional Modbus protocol JAR contains the fixed provider and service descriptor.' -Remediation 'Restore the exact protocol-modbus.jar and regenerate packages.json.' -Action {
        $protocol = Get-ManifestPackage -Manifest $context.Manifest -Id 'protocol-modbus'
        Test-ModbusProtocolJar -Path (Join-Path $context.PackageDirectory ([string]$protocol.file))
    }
    Invoke-PreflightCheck -Id 'nssm-architecture' -Category 'packages' -SuccessMessage 'NSSM executable is a native x64 PE file.' -Remediation 'Extract win64\nssm.exe from the fixed NSSM 2.24 archive.' -Action {
        $nssm = Get-ManifestPackage -Manifest $context.Manifest -Id 'nssm'
        $machine = Get-PeMachineType -Path (Join-Path $context.PackageDirectory ([string]$nssm.file))
        if ($machine -ne 0x8664) { throw ('NSSM PE machine is 0x{0:X4}, expected AMD64 0x8664.' -f $machine) }
    }
}

if ($configurationValid) {
    Invoke-PreflightCheck -Id 'administrator' -Category 'environment' -SuccessMessage 'The process has administrator rights.' -Remediation 'Run Check-GpLink.cmd from an account that can approve elevation.' -Action {
        if (-not (Test-IsAdministrator)) { throw 'Administrator rights are required.' }
    }
    Invoke-PreflightCheck -Id 'postgres-service-account' -Category 'environment' -SuccessMessage 'The configured PostgreSQL Windows service account is available.' -Remediation 'If the account already exists, configure its exact current password; otherwise use a password that satisfies the host password policy.' -Action {
        $accountText = [string]$config.PostgresServiceAccount
        $accountDomain = $env:COMPUTERNAME
        $accountUser = $accountText
        if ($accountText.Contains('\')) {
            $parts = $accountText.Split('\', 2)
            $accountDomain = if ($parts[0] -eq '.') { $env:COMPUTERNAME } else { $parts[0] }
            $accountUser = $parts[1]
        }
        $isLocal = [string]::Equals($accountDomain, $env:COMPUTERNAME, [System.StringComparison]::OrdinalIgnoreCase)
        if ($isLocal) {
            $escapedUser = $accountUser.Replace("'", "''")
            $existingAccount = Get-CimInstance Win32_UserAccount -Filter ("LocalAccount=True AND Name='{0}'" -f $escapedUser) | Select-Object -First 1
            if ($null -eq $existingAccount) { return "Local service account '$accountUser' does not yet exist; the PostgreSQL installer will create it subject to host password policy." }
            if ([bool]$existingAccount.Disabled) { throw "Existing local service account '$accountUser' is disabled." }
        }
        if (-not (Test-WindowsCredential -UserName $accountUser -Domain $accountDomain -Password ([string]$config.PostgresServicePassword))) {
            throw "The configured password does not authenticate the existing Windows service account '$accountText'."
        }
        return "Existing Windows service account '$accountText' accepted the configured password."
    }
    Invoke-PreflightCheck -Id 'windows-version' -Category 'environment' -SuccessMessage 'Supported x64 Windows Server or Windows 11 detected.' -Remediation 'Use 64-bit Windows Server 2019/2022/2025 or a supported Windows 11 build (22000, 22621, 22631, 26100, or 26200).' -Action {
        if (-not [Environment]::Is64BitOperatingSystem) { throw 'The operating system is not x64.' }
        $os = Get-CimInstance Win32_OperatingSystem
        $supportedRelease = Get-SupportedWindowsServerRelease -ProductType ([int]$os.ProductType) -BuildNumber ([int]$os.BuildNumber)
        if ([string]::IsNullOrWhiteSpace($supportedRelease)) { throw "Unsupported operating system: $($os.Caption), build $($os.BuildNumber)." }
        $family = if ([int]$os.ProductType -eq 1) { 'Windows ' } else { 'Windows Server ' }
        "Operating system: $family$supportedRelease ($($os.Caption)), build $($os.BuildNumber), $($os.OSArchitecture)."
    }
    Invoke-PreflightCheck -Id 'physical-memory' -Category 'environment' -SuccessMessage 'Physical memory meets the configured threshold.' -Remediation ("Install at least {0} GB physical memory." -f $config.MinimumMemoryGB) -Action {
        $memory = Get-PhysicalMemoryGB
        if ($memory -lt [double]$config.MinimumMemoryGB) { throw "Physical memory is $memory GB; at least $($config.MinimumMemoryGB) GB is required." }
        "Installed physical memory: $memory GB."
    }
    foreach ($diskCheck in @(
        @('app-disk', [string]$config.AppRoot, [double]$config.MinimumAppFreeGB),
        @('postgres-disk', [string]$config.PgData, [double]$config.MinimumPgFreeGB)
    )) {
        $checkId = $diskCheck[0]; $targetPath = $diskCheck[1]; $minimum = [double]$diskCheck[2]
        Invoke-PreflightCheck -Id $checkId -Category 'environment' -SuccessMessage 'Target disk is NTFS and has sufficient free space.' -Remediation "Provide an available NTFS volume with at least $minimum GB free." -Action {
            $drive = Get-DriveInfoForPath -Path $targetPath
            if (-not $drive.IsReady) { throw "Drive $($drive.Name) is not ready." }
            if ($drive.DriveFormat -ne 'NTFS') { throw "Drive $($drive.Name) uses $($drive.DriveFormat), not NTFS." }
            $free = [math]::Round($drive.AvailableFreeSpace / 1GB, 2)
            if ($free -lt $minimum) { throw "Drive $($drive.Name) has $free GB free; $minimum GB is required." }
            $probe = $targetPath
            while (-not (Test-Path -LiteralPath $probe) -and -not [string]::IsNullOrWhiteSpace((Split-Path -Parent $probe))) { $probe = Split-Path -Parent $probe }
            [void](Get-Acl -LiteralPath $probe)
            "Drive $($drive.Name) is NTFS with $free GB free; ACL metadata is readable."
        }
    }

    $state = $null
    $stateValid = $false
    if ($null -ne $context) {
        Invoke-PreflightCheck -Id 'install-state' -Category 'environment' -SuccessMessage 'No prior state exists, or the existing state matches this bundle.' -Remediation 'Do not overwrite an unknown installation. Restore the original bundle, or perform a separately planned upgrade.' -Action {
            if ($Reinstall) { return 'Destructive reinstall mode: state ownership will be checked before cleanup.' }
            if (Test-Path -LiteralPath $context.StatePath -PathType Leaf) {
                $script:state = Get-Content -LiteralPath $context.StatePath -Raw | ConvertFrom-Json
                $application = Get-ManifestPackage -Manifest $context.Manifest -Id 'application'
                $baseline = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
                if ([string]$state.bundleId -ne [string]$context.Manifest.bundleId -or [string]$state.appServiceName -ne [string]$config.AppServiceName -or [string]$state.pgServiceName -ne [string]$config.PgServiceName -or [string]$state.applicationSha256 -ne ([string]$application.sha256).ToUpperInvariant() -or [string]$state.baselineSqlSha256 -ne ([string]$baseline.sha256).ToUpperInvariant()) {
                    throw 'Existing install-state.json does not match this bundle, service names, application hash, or baseline SQL hash.'
                }
                $pathPairs = @(
                    @([string]$state.appRoot, [System.IO.Path]::GetFullPath([string]$config.AppRoot), 'AppRoot'),
                    @([string]$state.pgInstallRoot, [System.IO.Path]::GetFullPath([string]$config.PgInstallRoot), 'PgInstallRoot'),
                    @([string]$state.pgData, [System.IO.Path]::GetFullPath([string]$config.PgData), 'PgData')
                )
                foreach ($pair in $pathPairs) {
                    if (-not [string]::Equals($pair[0], $pair[1], [System.StringComparison]::OrdinalIgnoreCase)) { throw "Existing install state uses a different $($pair[2])." }
                }
                if ([int]$state.serverPort -ne [int]$config.ServerPort -or [int]$state.pgPort -ne [int]$config.PgPort -or [string]$state.databaseName -ne [string]$config.DatabaseName -or [string]$state.databaseUser -ne [string]$config.DatabaseUser) {
                    throw 'Existing install state uses different ports, database name, or database user.'
                }
                foreach ($currentPackage in $context.Manifest.packages) {
                    $oldPackage = @($state.packages | Where-Object { [string]$_.id -eq [string]$currentPackage.id })
                    if ($oldPackage.Count -ne 1 -or [string]$oldPackage[0].sha256 -ne ([string]$currentPackage.sha256).ToUpperInvariant() -or [string]$oldPackage[0].version -ne [string]$currentPackage.version) {
                        throw "Existing install state uses a different version or hash for package '$($currentPackage.id)'."
                    }
                }
                $script:stateValid = $true
                return 'Matching install-state.json found; repair mode is allowed.'
            }
            return 'No prior GPLink2 install state was found; new installation mode is allowed.'
        }
    }
    Invoke-PreflightCheck -Id 'firewall-rule-ownership' -Category 'environment' -SuccessMessage 'The target firewall rule name is free or belongs to this bundle.' -Remediation 'Rename or remove the unrelated firewall rule. This installer will modify only rules it owns.' -Action {
        $ruleName = Get-GpLinkFirewallRuleName -Port ([int]$config.ServerPort)
        $rule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
        if ($null -eq $rule) { return 'No existing target firewall rule was found.' }
        if ($Reinstall) { return 'Destructive reinstall mode: firewall ownership will be checked before cleanup.' }
        if (-not $stateValid -or -not (Test-GpLinkFirewallRuleOwned -Rule $rule -BundleId ([string]$context.Manifest.bundleId) -StatePath $context.StatePath)) {
            throw "Firewall rule '$ruleName' already exists but is not owned by this bundle."
        }
        return "Existing firewall rule '$ruleName' is owned by the matching installation and may be repaired or removed."
    }
    Invoke-PreflightCheck -Id 'service-names' -Category 'environment' -SuccessMessage 'Target service names are available or belong to this bundle.' -Remediation 'Remove or rename unrelated services; do not let this installer overwrite them.' -Action {
        if ($Reinstall) { return 'Destructive reinstall mode: service executable ownership will be checked before cleanup.' }
        foreach ($name in @([string]$config.PgServiceName, [string]$config.AppServiceName)) {
            if ($null -ne (Get-ServiceOrNull -Name $name) -and -not $stateValid) { throw "Service '$name' already exists without matching install state." }
        }
    }
    Invoke-PreflightCheck -Id 'installed-component-identity' -Category 'environment' -SuccessMessage 'Existing component versions and service executables match this bundle.' -Remediation 'Do not use v1 repair for version upgrades or services whose executable paths were changed.' -Action {
        if ($Reinstall) { return 'Destructive reinstall mode: component ownership will be checked before cleanup.' }
        if (-not $stateValid) { return 'No matching prior installation requires component identity checks.' }
        $pgPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'postgresql'
        $expectedPgVersion = ([string]$pgPackage.version -replace '-\d+$', '')
        $pgConfigPath = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_config.exe'
        if (Test-Path -LiteralPath $pgConfigPath -PathType Leaf) {
            $pgVersion = (& $pgConfigPath '--version' 2>&1 | Out-String).Trim()
            if ($LASTEXITCODE -ne 0 -or $pgVersion -notmatch ('^PostgreSQL\s+{0}(?:\s|$)' -f [regex]::Escape($expectedPgVersion))) { throw "Existing PostgreSQL is '$pgVersion', expected PostgreSQL $expectedPgVersion." }
        }
        $pgService = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ([string]$config.PgServiceName).Replace("'", "''")) -ErrorAction SilentlyContinue
        if ($null -ne $pgService) {
            $actualPgServiceExe = Get-WindowsServiceExecutablePath -PathName ([string]$pgService.PathName)
            $expectedPgServiceExe = [System.IO.Path]::GetFullPath((Join-Path ([string]$config.PgInstallRoot) 'bin\pg_ctl.exe'))
            if (-not [string]::Equals($actualPgServiceExe, $expectedPgServiceExe, [System.StringComparison]::OrdinalIgnoreCase)) { throw "PostgreSQL service executable is '$actualPgServiceExe', expected '$expectedPgServiceExe'." }
            $actualDataDirectory = Get-WindowsCommandLineArgumentValue -CommandLine ([string]$pgService.PathName) -ArgumentName '-D'
            if ([string]::IsNullOrWhiteSpace($actualDataDirectory) -or
                -not [string]::Equals([System.IO.Path]::GetFullPath($actualDataDirectory), [System.IO.Path]::GetFullPath([string]$config.PgData), [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "PostgreSQL service data directory is '$actualDataDirectory', expected '$($config.PgData)'."
            }
            if (-not (Test-WindowsAccountMatches -Expected ([string]$config.PostgresServiceAccount) -Actual ([string]$pgService.StartName))) {
                throw "PostgreSQL service runs as '$($pgService.StartName)', expected '$($config.PostgresServiceAccount)'."
            }
        }
        $appService = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ([string]$config.AppServiceName).Replace("'", "''")) -ErrorAction SilentlyContinue
        if ($null -ne $appService) {
            $actualAppServiceExe = Get-WindowsServiceExecutablePath -PathName ([string]$appService.PathName)
            $expectedAppServiceExe = [System.IO.Path]::GetFullPath((Join-Path ([string]$config.AppRoot) 'nssm.exe'))
            if (-not [string]::Equals($actualAppServiceExe, $expectedAppServiceExe, [System.StringComparison]::OrdinalIgnoreCase)) { throw "GPLink2 service executable is '$actualAppServiceExe', expected '$expectedAppServiceExe'." }
        }
        return 'Existing PostgreSQL version and service executable paths match this bundle.'
    }
    Invoke-PreflightCheck -Id 'target-occupancy' -Category 'environment' -SuccessMessage 'Target directories are free or belong to the matching installation.' -Remediation 'Choose empty target directories or restore the matching install-state.json and bundle.' -Action {
        if ($Reinstall) { return 'Destructive reinstall mode: target markers and reparse points will be checked before cleanup.' }
        if (-not $stateValid) {
            foreach ($path in @([string]$config.AppRoot, [string]$config.PgInstallRoot, [string]$config.PgData)) {
                if (Test-Path -LiteralPath $path) {
                    $first = Get-ChildItem -LiteralPath $path -Force -ErrorAction Stop | Select-Object -First 1
                    if ($null -ne $first) { throw "Target directory is already occupied: $path" }
                }
            }
        }
    }
    Invoke-PreflightCheck -Id 'port-availability' -Category 'environment' -SuccessMessage 'Application and PostgreSQL ports are available or owned by the matching installation.' -Remediation 'Stop the unrelated listener or select unused ServerPort and PgPort values.' -Action {
        if (Test-TcpPortInUse -Port ([int]$config.ServerPort)) {
            if ((-not $Reinstall -and -not $stateValid) -or -not (Test-TcpPortOwnedByService -Port ([int]$config.ServerPort) -ServiceName ([string]$config.AppServiceName))) { throw "TCP port $($config.ServerPort) is listening but is not owned by the matching GPLink2 service process tree." }
        }
        if (Test-TcpPortInUse -Port ([int]$config.PgPort)) {
            if ((-not $Reinstall -and -not $stateValid) -or -not (Test-TcpPortOwnedByService -Port ([int]$config.PgPort) -ServiceName ([string]$config.PgServiceName))) { throw "TCP port $($config.PgPort) is listening but is not owned by the matching PostgreSQL service process tree." }
        }
    }
    Invoke-PreflightCheck -Id 'external-redis' -Category 'environment' -SuccessMessage 'Redis AUTH, SELECT, and PING succeeded.' -Remediation 'Correct Redis host, port, credentials, ACL user, database number, or network access. Deployment cannot skip Redis validation.' -Action {
        [void](Test-ExternalRedis -Config $config)
    }
}

$failed = @($results | Where-Object { -not $_.passed })
$report = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    bundleRoot = $bundleRoot
    configPath = [System.IO.Path]::GetFullPath($ConfigPath)
    overallPassed = ($failed.Count -eq 0)
    passedCount = @($results | Where-Object { $_.passed }).Count
    failedCount = $failed.Count
    checks = @($results)
}
Write-JsonFile -InputObject $report -Path $reportPath
$textLines = @(
    'GPLink2 offline deployment preflight',
    ('Generated: {0}' -f $report.generatedAt),
    ('Overall: {0}' -f $(if ($report.overallPassed) { 'PASS' } else { 'FAIL' })),
    ('Passed: {0}; Failed: {1}' -f $report.passedCount, $report.failedCount),
    ''
)
foreach ($result in $results) {
    $textLines += ('{0} {1}/{2}: {3}' -f $(if ($result.passed) { '[PASS]' } else { '[FAIL]' }), $result.category, $result.id, $result.message)
    if (-not $result.passed -and -not [string]::IsNullOrWhiteSpace($result.remediation)) { $textLines += ('  Fix: ' + $result.remediation) }
}
Set-Content -LiteralPath $textReportPath -Value $textLines -Encoding UTF8

if ($failed.Count -gt 0) {
    Write-DeployLog -Level 'ERROR' -Message "Preflight failed: $($failed.Count) check(s) failed. Reports: $reportPath and $textReportPath"
    exit 1
}
Write-DeployLog -Message "Preflight passed. Reports: $reportPath and $textReportPath"
exit 0
