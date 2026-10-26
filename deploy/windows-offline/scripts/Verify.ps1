[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [switch]$AllowIncompleteState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$context = Get-DeploymentContext -ConfigPath $ConfigPath
$config = $context.Config
$logDirectory = Join-Path $context.BundleRoot 'logs'
$reportPath = Join-Path $logDirectory 'verify-report.json'
$textReportPath = Join-Path $logDirectory 'verify-report.txt'
Initialize-DeployLog -LogPath (Join-Path $logDirectory ('verify-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))) -Config $config
$results = New-Object System.Collections.ArrayList

function Add-VerifyResult {
    param([string]$Id, [bool]$Passed, [string]$Message, [string]$Remediation = '')
    [void]$results.Add((New-CheckResult -Id $Id -Category 'verification' -Passed $Passed -Message $Message -Remediation $Remediation))
    Write-Host ("{0} {1} - {2}" -f $(if ($Passed) { '[PASS]' } else { '[FAIL]' }), $Id, (Protect-LogText -Text $Message))
}

function Invoke-VerifyCheck {
    param([string]$Id, [scriptblock]$Action, [string]$SuccessMessage, [string]$Remediation)
    try {
        $detail = & $Action
        Add-VerifyResult -Id $Id -Passed $true -Message $(if ($null -ne $detail -and -not [string]::IsNullOrWhiteSpace([string]$detail)) { [string]$detail } else { $SuccessMessage })
    } catch {
        Add-VerifyResult -Id $Id -Passed $false -Message $_.Exception.Message -Remediation $Remediation
    }
}

$psql = Join-Path ([string]$config.PgInstallRoot) 'bin\psql.exe'
$pgIsReady = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_isready.exe'
$pgConfig = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_config.exe'
$pgCtl = Join-Path ([string]$config.PgInstallRoot) 'bin\pg_ctl.exe'
$superUser = [string]$config.PostgresSuperUser
$superPassword = [string]$config.PostgresSuperPassword
$database = [string]$config.DatabaseName
$port = [int]$config.PgPort
$script:verificationToken = $null
$verificationState = $null
if (Test-Path -LiteralPath $context.StatePath -PathType Leaf) {
    try { $verificationState = Get-Content -LiteralPath $context.StatePath -Raw | ConvertFrom-Json } catch { }
}
    $javaRuntimeMode = 'BundledJdk'
$javaRuntimePath = Join-Path ([string]$config.AppRoot) 'runtime\java\bin\java.exe'
if ($null -ne $verificationState -and $verificationState.PSObject.Properties.Name -contains 'javaRuntimeMode' -and
    $verificationState.PSObject.Properties.Name -contains 'javaRuntimePath') {
    $javaRuntimeMode = [string]$verificationState.javaRuntimeMode
    $javaRuntimePath = [string]$verificationState.javaRuntimePath
}

Invoke-VerifyCheck -Id 'install-state' -SuccessMessage 'Install state matches this bundle.' -Remediation 'Run Install-GpLink.cmd in repair mode with the original bundle.' -Action {
    if (-not (Test-Path -LiteralPath $context.StatePath -PathType Leaf)) { throw 'install-state.json does not exist.' }
    $state = Get-Content -LiteralPath $context.StatePath -Raw | ConvertFrom-Json
    $application = Get-ManifestPackage -Manifest $context.Manifest -Id 'application'
    $baseline = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
    if ([string]$state.bundleId -ne [string]$context.Manifest.bundleId -or [string]$state.applicationSha256 -ne ([string]$application.sha256).ToUpperInvariant() -or [string]$state.baselineSqlSha256 -ne ([string]$baseline.sha256).ToUpperInvariant()) { throw 'Install state does not match the current bundle, application hash, or baseline SQL hash.' }
    if ([string]$state.appServiceName -ne [string]$config.AppServiceName -or [string]$state.pgServiceName -ne [string]$config.PgServiceName -or
        -not [string]::Equals([string]$state.appRoot, [System.IO.Path]::GetFullPath([string]$config.AppRoot), [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals([string]$state.pgInstallRoot, [System.IO.Path]::GetFullPath([string]$config.PgInstallRoot), [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals([string]$state.pgData, [System.IO.Path]::GetFullPath([string]$config.PgData), [System.StringComparison]::OrdinalIgnoreCase) -or
        [int]$state.serverPort -ne [int]$config.ServerPort -or [int]$state.pgPort -ne [int]$config.PgPort -or
        [string]$state.databaseName -ne [string]$config.DatabaseName -or [string]$state.databaseUser -ne [string]$config.DatabaseUser) {
        throw 'Install state service names, paths, ports, or database identity differ from the current configuration.'
    }
    foreach ($currentPackage in $context.Manifest.packages) {
        $statePackage = @($state.packages | Where-Object { [string]$_.id -eq [string]$currentPackage.id })
        if ($statePackage.Count -ne 1 -or [string]$statePackage[0].version -ne [string]$currentPackage.version -or
            [string]$statePackage[0].sha256 -ne ([string]$currentPackage.sha256).ToUpperInvariant()) { throw "Install state differs for package '$($currentPackage.id)'." }
    }
    if (-not $AllowIncompleteState -and [string]$state.status -ne 'Complete') { throw "Installation state is '$($state.status)', not Complete." }
}
Invoke-VerifyCheck -Id 'postgres-service' -SuccessMessage 'PostgreSQL service is running and automatic.' -Remediation 'Inspect PostgreSQL service and Windows Event Viewer.' -Action {
    $service = Get-Service -Name ([string]$config.PgServiceName) -ErrorAction Stop
    $wmi = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ([string]$config.PgServiceName).Replace("'", "''"))
    if ($service.Status -ne 'Running' -or [string]$wmi.StartMode -ne 'Auto') { throw "PostgreSQL service status/start mode is $($service.Status)/$($wmi.StartMode)." }
    $serviceExecutable = Get-WindowsServiceExecutablePath -PathName ([string]$wmi.PathName)
    if (-not [string]::Equals($serviceExecutable, [System.IO.Path]::GetFullPath($pgCtl), [System.StringComparison]::OrdinalIgnoreCase)) { throw "PostgreSQL service uses '$serviceExecutable', expected '$pgCtl'." }
    $serviceDataDirectory = Get-WindowsCommandLineArgumentValue -CommandLine ([string]$wmi.PathName) -ArgumentName '-D'
    if ([string]::IsNullOrWhiteSpace($serviceDataDirectory) -or
        -not [string]::Equals([System.IO.Path]::GetFullPath($serviceDataDirectory), [System.IO.Path]::GetFullPath([string]$config.PgData), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PostgreSQL service data directory is '$serviceDataDirectory', expected '$($config.PgData)'."
    }
    if (-not (Test-WindowsAccountMatches -Expected ([string]$config.PostgresServiceAccount) -Actual ([string]$wmi.StartName))) { throw "PostgreSQL service runs as '$($wmi.StartName)', expected '$($config.PostgresServiceAccount)'." }
    $ready = & $pgIsReady '-h' '127.0.0.1' '-p' ([string]$port) 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('pg_isready failed: ' + ($ready | Out-String).Trim()) }
}
Invoke-VerifyCheck -Id 'postgres-version' -SuccessMessage 'PostgreSQL binaries and running server match the exact manifest version.' -Remediation 'Restore the PostgreSQL version locked by this bundle; v1 does not support upgrades.' -Action {
    $package = Get-ManifestPackage -Manifest $context.Manifest -Id 'postgresql'
    $expected = ([string]$package.version -replace '-\d+$', '')
    $binaryVersion = (& $pgConfig '--version' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $binaryVersion -notmatch ('^PostgreSQL\s+{0}(?:\s|$)' -f [regex]::Escape($expected))) { throw "pg_config reports '$binaryVersion', expected PostgreSQL $expected." }
    $serverVersion = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql 'SHOW server_version;') -join '').Trim()
    if ($serverVersion -ne $expected) { throw "Running PostgreSQL version is '$serverVersion', expected '$expected'." }
    "PostgreSQL version: $serverVersion."
}
Invoke-VerifyCheck -Id 'postgres-local-only' -SuccessMessage 'PostgreSQL listens only on the local loopback address.' -Remediation 'Restore the managed listen_addresses setting and restart PostgreSQL.' -Action {
    $addresses = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql 'SHOW listen_addresses;') -join '').Trim()
    if ($addresses -ne '127.0.0.1') { throw "listen_addresses is '$addresses', expected 127.0.0.1." }
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $unexpected = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }
        if ($null -ne ($unexpected | Select-Object -First 1)) { throw 'PostgreSQL has a non-loopback listening socket.' }
    }
}
Invoke-VerifyCheck -Id 'timescaledb-version' -SuccessMessage 'TimescaleDB extension version matches the manifest.' -Remediation 'Re-run Install-GpLink.cmd with the original TimescaleDB package.' -Action {
    $expected = [string](Get-ManifestPackage -Manifest $context.Manifest -Id 'timescaledb').version
    $actual = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT extversion FROM pg_extension WHERE extname='timescaledb';") -join '').Trim()
    if ($actual -ne $expected) { throw "TimescaleDB version is '$actual', expected '$expected'." }
    "TimescaleDB extension version: $actual."
}
Invoke-VerifyCheck -Id 'database-role' -SuccessMessage 'Application database role is a non-superuser owner and can connect.' -Remediation 'Run repair to restore database role ownership and privileges.' -Action {
    $databaseLiteral = Quote-PgLiteral -Value ([string]$config.DatabaseName)
    $roleLiteral = Quote-PgLiteral -Value ([string]$config.DatabaseUser)
    $sql = "SELECT CASE WHEN d.datdba=r.oid AND r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole THEN 'OK' ELSE 'BAD' END FROM pg_database d JOIN pg_roles r ON r.rolname=$roleLiteral WHERE d.datname=$databaseLiteral;"
    $status = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database 'postgres' -Port $port -Sql $sql) -join '').Trim()
    if ($status -ne 'OK') { throw 'Application role is missing, privileged, or not the database owner.' }
    $currentUser = ((Invoke-Psql -PsqlPath $psql -Password ([string]$config.DatabasePassword) -User ([string]$config.DatabaseUser) -Database $database -Port $port -Sql 'SELECT current_user;') -join '').Trim()
    if ($currentUser -ne [string]$config.DatabaseUser) { throw 'Application role connection returned an unexpected user.' }
}
Invoke-VerifyCheck -Id 'resource-baseline' -SuccessMessage 'The one-time resource baseline marker and retained GPLink2 resource set are exact.' -Remediation 'Do not rerun the destructive SQL manually. Restore the database from backup or perform an explicitly planned cleanup.' -Action {
    $baseline = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
    $schemaIdentifier = Quote-PgIdentifier -Value ([string]$config.DatabaseSchema)
    $markerTableLiteral = Quote-PgLiteral -Value ([string]$config.DatabaseSchema + '.gplink_deployment_baseline')
    $exists = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT CASE WHEN to_regclass($markerTableLiteral) IS NULL THEN '0' ELSE '1' END;") -join '').Trim()
    if ($exists -ne '1') { throw 'The one-time baseline marker table is missing.' }
    $marker = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT bundle_id || '|' || sql_sha256 FROM $schemaIdentifier.gplink_deployment_baseline ORDER BY applied_at DESC LIMIT 1;") -join '').Trim()
    $expectedMarker = [string]$context.Manifest.bundleId + '|' + ([string]$baseline.sha256).ToUpperInvariant()
    if ($marker -ne $expectedMarker) { throw 'The baseline marker does not match this bundle and SQL hash.' }
    $actual = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql @"
SELECT
    (SELECT count(*) FROM $schemaIdentifier.dev_product) || '|' ||
    (SELECT count(*) FROM $schemaIdentifier.dev_device_instance) || '|' ||
    (SELECT count(*) FROM $schemaIdentifier.device_gateway) || '|' ||
    COALESCE((SELECT string_agg(id, ',' ORDER BY id) FROM $schemaIdentifier.dev_protocol), '') || '|' ||
    COALESCE((SELECT string_agg(id, ',' ORDER BY id) FROM $schemaIdentifier.network_config), '');
"@) -join '').Trim()
    $expected = '0|0|0||2068926208973430784,2068932624811515904'
    if ($actual -ne $expected) { throw 'Products, devices, gateways, protocol registrations, or retained network components differ from the approved baseline.' }
}
Invoke-VerifyCheck -Id 'external-redis' -SuccessMessage 'Redis AUTH, SELECT, and PING succeeded.' -Remediation 'Restore Redis connectivity and credentials.' -Action { [void](Test-ExternalRedis -Config $config) }
Invoke-VerifyCheck -Id 'installed-files' -SuccessMessage 'Installed JAR, NSSM, and selected Java 17 runtime are valid.' -Remediation 'Run repair with the original offline bundle and check the recorded Java path.' -Action {
    $installedFiles = @(
        [pscustomobject]@{ PackageId = 'application'; RelativePath = 'application.jar' },
        [pscustomobject]@{ PackageId = 'nssm'; RelativePath = 'nssm.exe' }
    )
    foreach ($installedFile in $installedFiles) {
        $package = Get-ManifestPackage -Manifest $context.Manifest -Id $installedFile.PackageId
        $installedPath = Join-Path ([string]$config.AppRoot) $installedFile.RelativePath
        if (-not (Test-Path -LiteralPath $installedPath -PathType Leaf)) { throw "Installed file is missing: $($installedFile.RelativePath)" }
        if ((Get-FileHash -LiteralPath $installedPath -Algorithm SHA256).Hash -ne ([string]$package.sha256).ToUpperInvariant()) { throw "Installed file hash differs from the bundle: $($installedFile.RelativePath)" }
    }
    if ($javaRuntimeMode -notin @('SystemJdk', 'BundledJdk')) { throw "Unknown Java runtime mode in install state: $javaRuntimeMode" }
    $expectedJavaRuntimePath = if ($javaRuntimeMode -eq 'SystemJdk') {
        'D:\Program Files\Java\jdk-17\bin\java.exe'
    } else {
        Join-Path ([string]$config.AppRoot) 'runtime\java\bin\java.exe'
    }
    if (-not [string]::Equals([System.IO.Path]::GetFullPath($javaRuntimePath), [System.IO.Path]::GetFullPath($expectedJavaRuntimePath), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Recorded Java executable path does not match runtime mode '$javaRuntimeMode'."
    }
    $expectedJavaVersion = $null
    if ($javaRuntimeMode -eq 'BundledJdk') {
        $javaPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'java'
        $expectedJavaVersion = ([string]$javaPackage.version -split '\+')[0]
    }
    $javaInfo = Get-JavaRuntimeInfo -JavaPath $javaRuntimePath -ExpectedVersion $expectedJavaVersion -RequireJdk
    if ($null -ne $verificationState -and $verificationState.PSObject.Properties.Name -contains 'javaRuntimeVersion' -and
        -not [string]::IsNullOrWhiteSpace([string]$verificationState.javaRuntimeVersion) -and
        [string]$javaInfo.Version -ne [string]$verificationState.javaRuntimeVersion) {
        throw "Java runtime version changed from recorded $($verificationState.javaRuntimeVersion) to $($javaInfo.Version)."
    }
    "Verified $javaRuntimeMode Java $($javaInfo.Version) at $($javaInfo.JavaPath)."
}
Invoke-VerifyCheck -Id 'runtime-prerequisites' -SuccessMessage 'Installed Visual C++ and OpenSSL runtimes meet the bundle requirements.' -Remediation 'Re-run repair with the original VC++ and OpenSSL packages.' -Action {
    $vcPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'vcredist'
    $vc = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64' -ErrorAction Stop
    $installedVcVersion = ([string]$vc.Version).TrimStart('v')
    if ([int]$vc.Installed -ne 1 -or [version]$installedVcVersion -lt [version][string]$vcPackage.version) { throw "Visual C++ x64 runtime is '$installedVcVersion', required version is at least '$($vcPackage.version)'." }
    $openSslPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'openssl'
    $openSslExe = Join-Path ([string]$config.OpenSslInstallRoot) 'bin\openssl.exe'
    if (-not (Test-Path -LiteralPath $openSslExe -PathType Leaf)) { throw "OpenSSL executable is missing: $openSslExe" }
    $openSslVersion = (& $openSslExe 'version' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $openSslVersion -notmatch ('^OpenSSL\s+{0}(?:\s|$)' -f [regex]::Escape([string]$openSslPackage.version))) { throw "Installed OpenSSL reports '$openSslVersion', expected $($openSslPackage.version)." }
    "Visual C++ $installedVcVersion and OpenSSL $($openSslPackage.version) are installed."
}
Invoke-VerifyCheck -Id 'sensitive-file-acl' -SuccessMessage 'Source and runtime configuration ACLs are restricted to their required identities.' -Remediation 'Run repair to restore the deployment and external configuration ACLs.' -Action {
    $aclChecks = @(
        [pscustomobject]@{ Path = (Join-Path ([string]$config.AppRoot) 'config\application-deploy.yml'); Allowed = @('S-1-5-18', 'S-1-5-19', 'S-1-5-32-544') },
        [pscustomobject]@{ Path = [System.IO.Path]::GetFullPath($context.ConfigPath); Allowed = @('S-1-5-18', 'S-1-5-32-544') },
        [pscustomobject]@{ Path = (Join-Path $context.PackageDirectory ([string](Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql').file)); Allowed = @('S-1-5-18', 'S-1-5-32-544') }
    )
    foreach ($aclCheck in $aclChecks) {
        $acl = Get-Acl -LiteralPath $aclCheck.Path
        $found = @()
        foreach ($rule in $acl.Access) {
            if ($rule.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow) { continue }
            $sid = $rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value
            if ($aclCheck.Allowed -notcontains $sid) { throw "Unexpected read-capable identity '$sid' on $($aclCheck.Path)." }
            $found += $sid
        }
        foreach ($requiredSid in $aclCheck.Allowed) {
            if ($found -notcontains $requiredSid) { throw "Required identity '$requiredSid' is missing from $($aclCheck.Path)." }
        }
    }
}
Invoke-VerifyCheck -Id 'gplink-service' -SuccessMessage 'GPLink2 service is running with delayed automatic startup.' -Remediation 'Inspect NSSM configuration and service stdout/stderr logs.' -Action {
    $service = Get-Service -Name ([string]$config.AppServiceName) -ErrorAction Stop
    $wmi = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ([string]$config.AppServiceName).Replace("'", "''"))
    if ($service.Status -ne 'Running' -or [string]$wmi.StartMode -ne 'Auto') { throw "GPLink2 service status/start mode is $($service.Status)/$($wmi.StartMode)." }
    $expectedNssm = [System.IO.Path]::GetFullPath((Join-Path ([string]$config.AppRoot) 'nssm.exe'))
    $serviceExecutable = Get-WindowsServiceExecutablePath -PathName ([string]$wmi.PathName)
    if (-not [string]::Equals($serviceExecutable, $expectedNssm, [System.StringComparison]::OrdinalIgnoreCase)) { throw "GPLink2 service uses '$serviceExecutable', expected '$expectedNssm'." }
    $normalizedStartName = ([string]$wmi.StartName).Replace(' ', '')
    if (-not [string]::Equals($normalizedStartName, 'NTAUTHORITY\LocalService', [System.StringComparison]::OrdinalIgnoreCase)) { throw "GPLink2 service runs as '$($wmi.StartName)', expected LocalService." }
    $registryPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\' + [string]$config.AppServiceName
    $delayed = (Get-ItemProperty -LiteralPath $registryPath -Name DelayedAutoStart -ErrorAction SilentlyContinue).DelayedAutoStart
    if ([int]$delayed -ne 1) { throw 'GPLink2 is not configured for delayed automatic startup.' }
    $dependencies = @((Get-ItemProperty -LiteralPath $registryPath -Name DependOnService -ErrorAction SilentlyContinue).DependOnService)
    if ($dependencies -notcontains [string]$config.PgServiceName) { throw 'GPLink2 does not depend on the configured PostgreSQL service.' }
    $parametersPath = Join-Path $registryPath 'Parameters'
    $parameters = Get-ItemProperty -LiteralPath $parametersPath -ErrorAction Stop
    $javaPath = [System.IO.Path]::GetFullPath($javaRuntimePath)
    $jarPath = [System.IO.Path]::GetFullPath((Join-Path ([string]$config.AppRoot) 'application.jar'))
    $externalConfigPath = [System.IO.Path]::GetFullPath((Join-Path ([string]$config.AppRoot) 'config\application-deploy.yml'))
    $jvmProfile = Get-ResolvedJvmProfile -Config $config
    $expectedParameters = Get-GpLinkServiceAppParameters -Config $config -JarPath $jarPath -ExternalConfigPath $externalConfigPath -JvmProfile $jvmProfile
    $expectedValues = @{
        Application = $javaPath; AppDirectory = [System.IO.Path]::GetFullPath([string]$config.AppRoot); AppParameters = $expectedParameters
        AppStdout = (Join-Path ([string]$config.AppRoot) 'data\logs\service\stdout.log'); AppStderr = (Join-Path ([string]$config.AppRoot) 'data\logs\service\stderr.log')
        AppRotateFiles = 1; AppRotateOnline = 1; AppRotateSeconds = 86400; AppRotateBytes = 10485760
        AppRestartDelay = 10000; AppThrottle = 1500; AppNoConsole = 1
    }
    foreach ($entry in $expectedValues.GetEnumerator()) {
        if ($entry.Key -eq 'AppParameters') {
            $actualParametersProperty = $parameters.PSObject.Properties[$entry.Key]
            if ($null -eq $actualParametersProperty) { throw "NSSM setting '$($entry.Key)' is missing from the service registry." }
            $expectedTokens = @(ConvertTo-NssmArgumentTokens -CommandLine ([string]$entry.Value))
            $actualTokens = @(ConvertTo-NssmArgumentTokens -CommandLine ([string]$actualParametersProperty.Value))
            if ($expectedTokens.Count -ne $actualTokens.Count) { throw "NSSM setting '$($entry.Key)' differs from the managed value." }
            for ($index = 0; $index -lt $expectedTokens.Count; $index++) {
                if (-not [string]::Equals($expectedTokens[$index], $actualTokens[$index], [System.StringComparison]::OrdinalIgnoreCase)) {
                    throw "NSSM setting '$($entry.Key)' differs from the managed value."
                }
            }
            continue
        }
        $actualProperty = $parameters.PSObject.Properties[$entry.Key]
        if ($null -eq $actualProperty) { throw "NSSM setting '$($entry.Key)' is missing from the service registry." }
        if ([string]$actualProperty.Value -ne [string]$entry.Value) { throw "NSSM setting '$($entry.Key)' differs from the managed value." }
    }
    $exitParameters = Get-ItemProperty -LiteralPath (Join-Path $parametersPath 'AppExit') -ErrorAction Stop
    $exitActionProperty = $exitParameters.PSObject.Properties['Default']
    if ($null -eq $exitActionProperty) { throw "NSSM default exit action is missing from the service registry; expected Restart." }
    if ([string]$exitActionProperty.Value -ne 'Restart') { throw "NSSM default exit action is '$($exitActionProperty.Value)', expected Restart." }
}
Invoke-VerifyCheck -Id 'api-firewall' -SuccessMessage 'API firewall rule allows Domain and Private only.' -Remediation 'Run repair to restore the scoped GPLink2 API firewall rule.' -Action {
    $ruleName = Get-GpLinkFirewallRuleName -Port ([int]$config.ServerPort)
    if (-not [bool]$config.OpenApiFirewall) {
        if ($null -ne (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)) { throw "Firewall rule '$ruleName' remains present although OpenApiFirewall is false." }
        return 'API firewall creation is disabled and no managed rule remains.'
    }
    $rule = Get-NetFirewallRule -Name $ruleName -ErrorAction Stop
    if (-not (Test-GpLinkFirewallRuleOwned -Rule $rule -BundleId ([string]$context.Manifest.bundleId) -StatePath $context.StatePath)) { throw 'API firewall rule is not owned by this bundle.' }
    $profiles = @(([string]$rule.Profile -split ',') | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $expectedProfiles = @([string[]]$config.FirewallProfiles | Sort-Object -Unique)
    if ($rule.Enabled -ne 'True' -or $rule.Direction -ne 'Inbound' -or $rule.Action -ne 'Allow' -or (Compare-Object -ReferenceObject $expectedProfiles -DifferenceObject @($profiles | Sort-Object -Unique))) { throw 'API firewall rule is disabled, has the wrong action/direction, or does not match FirewallProfiles.' }
    $filter = $rule | Get-NetFirewallPortFilter
    if ([string]$filter.Protocol -ne 'TCP' -or [string]$filter.LocalPort -ne [string]$config.ServerPort) { throw 'API firewall rule has the wrong protocol or port.' }
    $addressFilter = $rule | Get-NetFirewallAddressFilter
    $remoteAddresses = @($addressFilter.RemoteAddress)
    if ($remoteAddresses.Count -ne 1 -or [string]$remoteAddresses[0] -ne 'Any') { throw 'API firewall rule does not allow the configured Domain/Private networks from any remote address.' }
}
Invoke-VerifyCheck -Id 'api-login' -SuccessMessage 'API port is listening and login returned a token.' -Remediation 'Inspect application startup logs, database schema initialization, Redis, and credentials.' -Action {
    Wait-TcpPort -HostName '127.0.0.1' -Port ([int]$config.ServerPort) -TimeoutSeconds ([int]$config.ApiVerifyTimeoutSeconds)
    $uri = "http://127.0.0.1:$($config.ServerPort)/authorize/login"
    $payload = @{ username = [string]$config.AdminUsername; password = [string]$config.AdminUserPassword; expires = 3600000 } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' -Body $payload -TimeoutSec 30
    $successValue = Get-OptionalObjectProperty -Object $response -Name 'success'
    $success = if ($null -eq $successValue) { $true } else { [bool]$successValue }
    $token = $null
    if ($response -is [string]) {
        $token = [string]$response
    } else {
        foreach ($candidate in @(
            (Get-OptionalObjectProperty -Object $response -Name 'result'),
            (Get-OptionalObjectProperty -Object $response -Name 'data'),
            $response
        )) {
            if ($null -eq $candidate) { continue }
            $candidateToken = if ($candidate -is [string]) { [string]$candidate } else { Get-OptionalObjectProperty -Object $candidate -Name 'token' }
            if (-not [string]::IsNullOrWhiteSpace([string]$candidateToken)) { $token = [string]$candidateToken; break }
        }
    }
    if (-not $success -or [string]::IsNullOrWhiteSpace([string]$token)) { throw 'Login response did not contain a valid token.' }
    # Invoke-VerifyCheck executes the action in a child scope. Keep the
    # short-lived token scoped to this verification script only.
    $script:verificationToken = [string]$token
}
$script:verificationToken = $null
Invoke-VerifyCheck -Id 'application-logs' -SuccessMessage 'Application logs exist and contain no startup-fatal signature.' -Remediation 'Inspect the newest files under AppRoot\data\logs and AppRoot\data\logs\service.' -Action {
    $appLogs = @(Get-ChildItem -LiteralPath (Join-Path ([string]$config.AppRoot) 'data\logs') -File -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Length -gt 0 } | Sort-Object LastWriteTime -Descending)
    if ($appLogs.Count -eq 0) { throw 'No non-empty application or service log file was created.' }
    $recent = @($appLogs | Select-Object -First 4)
    foreach ($log in $recent) {
        $tail = Get-Content -LiteralPath $log.FullName -Tail 500 -ErrorAction Stop | Out-String
        if ($tail -match '(?i)APPLICATION FAILED TO START|OutOfMemoryError|Address already in use|Unable to start embedded|Failed to initialize ApplicationContext') {
            throw "Startup-fatal signature found in $($log.Name)."
        }
    }
    "Verified $($recent.Count) recent non-empty log file(s)."
}

$failed = @($results | Where-Object { -not $_.passed })
$report = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    overallPassed = ($failed.Count -eq 0)
    passedCount = @($results | Where-Object { $_.passed }).Count
    failedCount = $failed.Count
    checks = @($results)
}
Write-JsonFile -InputObject $report -Path $reportPath
$lines = @('GPLink2 deployment verification', ('Generated: ' + $report.generatedAt), ('Overall: ' + $(if ($report.overallPassed) { 'PASS' } else { 'FAIL' })), '')
foreach ($result in $results) {
    $lines += ('{0} {1}: {2}' -f $(if ($result.passed) { '[PASS]' } else { '[FAIL]' }), $result.id, $result.message)
    if (-not $result.passed -and -not [string]::IsNullOrWhiteSpace($result.remediation)) { $lines += ('  Fix: ' + $result.remediation) }
}
Set-Content -LiteralPath $textReportPath -Value $lines -Encoding UTF8
if ($failed.Count -gt 0) { Write-DeployLog -Level 'ERROR' -Message "Verification failed: $($failed.Count) check(s)."; exit 1 }
$verifiedState = Get-Content -LiteralPath $context.StatePath -Raw | ConvertFrom-Json
$verificationTimestamp = (Get-Date).ToUniversalTime().ToString('o')
if ($verifiedState.PSObject.Properties.Name -contains 'lastVerifiedAt') { $verifiedState.lastVerifiedAt = $verificationTimestamp }
else { $verifiedState | Add-Member -NotePropertyName lastVerifiedAt -NotePropertyValue $verificationTimestamp }
if (-not $AllowIncompleteState -and $verifiedState.PSObject.Properties.Name -contains 'completedAt') {
    $bootTime = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime()
    $completionTime = [DateTimeOffset]::Parse([string]$verifiedState.completedAt).UtcDateTime
    if ($bootTime -gt $completionTime) {
        $verifiedState.rebootVerificationRequired = $false
        if ($verifiedState.PSObject.Properties.Name -contains 'rebootVerifiedAt') { $verifiedState.rebootVerifiedAt = $verifiedState.lastVerifiedAt }
        else { $verifiedState | Add-Member -NotePropertyName rebootVerifiedAt -NotePropertyValue $verifiedState.lastVerifiedAt }
    }
}
Write-JsonFile -InputObject $verifiedState -Path $context.StatePath -Depth 8
Set-RestrictedAcl -Path $context.StatePath
Write-DeployLog -Message 'Verification passed. Login token was validated and discarded.'
exit 0
