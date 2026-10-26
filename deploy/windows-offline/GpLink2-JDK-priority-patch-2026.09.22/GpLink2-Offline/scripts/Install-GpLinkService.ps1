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
$javaPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'java'
$systemJdk = Get-PreferredSystemJdk
$useBundledJava = -not [bool]$systemJdk.Available
$applicationPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'application'
$nssmPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'nssm'
$appRoot = [string]$config.AppRoot
$runtimeRoot = Join-Path $appRoot 'runtime\java'
$configDirectory = Join-Path $appRoot 'config'
$dataDirectory = Join-Path $appRoot 'data'
$logDirectory = Join-Path $dataDirectory 'logs'
$serviceLogDirectory = Join-Path $logDirectory 'service'
$jarPath = Join-Path $appRoot 'application.jar'
$nssmPath = Join-Path $appRoot 'nssm.exe'
$externalConfigPath = Join-Path $configDirectory 'application-deploy.yml'
$serviceName = [string]$config.AppServiceName
$serviceAlreadyExists = $null -ne (Get-ServiceOrNull -Name $serviceName)
$expectedServiceExecutable = [System.IO.Path]::GetFullPath($nssmPath)

if ($serviceAlreadyExists) {
    $existingService = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $serviceName.Replace("'", "''"))
    $existingExecutable = Get-WindowsServiceExecutablePath -PathName ([string]$existingService.PathName)
    if (-not [string]::Equals($existingExecutable, $expectedServiceExecutable, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Existing service '$serviceName' uses '$existingExecutable', expected '$expectedServiceExecutable'."
    }
    if ((Get-Service -Name $serviceName).Status -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds([int]$config.ServiceStartTimeoutSeconds))
    }
}

New-Item -ItemType Directory -Path $appRoot -Force | Out-Null
$yamlLines = @(
    ('DB_HOST: ' + (ConvertTo-YamlSingleQuoted '127.0.0.1')),
    ('DB_PORT: ' + [string]$config.PgPort),
    ('DB_DATABASE: ' + (ConvertTo-YamlSingleQuoted ([string]$config.DatabaseName))),
    ('DB_USERNAME: ' + (ConvertTo-YamlSingleQuoted ([string]$config.DatabaseUser))),
    ('DB_PASSWORD: ' + (ConvertTo-YamlSingleQuoted ([string]$config.DatabasePassword))),
    ('DB_SCHEMA: ' + (ConvertTo-YamlSingleQuoted ([string]$config.DatabaseSchema))),
    "DB_DIALECT: 'postgres'",
    ('REDIS_HOST: ' + (ConvertTo-YamlSingleQuoted ([string]$config.RedisHost))),
    ('REDIS_PORT: ' + [string]$config.RedisPort),
    ('REDIS_PASSWORD: ' + (ConvertTo-YamlSingleQuoted ([string]$config.RedisPassword))),
    ('REDIS_DATABASE: ' + [string]$config.RedisDatabase),
    ('TIMESCALEDB_SCHEMA: ' + (ConvertTo-YamlSingleQuoted ([string]$config.DatabaseSchema))),
    ('ADMIN_USER_PASSWORD: ' + (ConvertTo-YamlSingleQuoted ([string]$config.AdminUserPassword))),
    ('EXTERNAL_HOST: ' + (ConvertTo-YamlSingleQuoted ([string]$config.ExternalHost))),
    ('EXTERNAL_PORT: ' + [string]$config.ExternalPort),
    'server:',
    ('  port: ' + [string]$config.ServerPort)
)
if (-not [string]::IsNullOrWhiteSpace([string]$config.RedisUser)) {
    $yamlLines += @('spring:', '  data:', '    redis:', ('      username: ' + (ConvertTo-YamlSingleQuoted ([string]$config.RedisUser))))
}

function Invoke-Nssm {
    param([string[]]$Arguments, [string]$Description)
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $nssmPath @Arguments 2>&1
        $nssmExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($nssmExitCode -ne 0) { throw "$Description failed: $(($output | Out-String).Trim())" }
}

function Set-NssmRegistryDword {
    param(
        [Parameter(Mandatory = $true)][string]$ServiceName,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Value
    )
    # NSSM 2.24 treats AppThrottle=1500 as its built-in default and may omit
    # the registry value after `nssm set`.  Write managed numeric settings
    # explicitly as REG_DWORD so verification is deterministic on all hosts.
    $subKeyPath = 'SYSTEM\CurrentControlSet\Services\' + $ServiceName + '\Parameters'
    $registryKey = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($subKeyPath, $true)
    if ($null -eq $registryKey) { throw "NSSM Parameters registry key is missing for service '$ServiceName'." }
    try {
        $registryKey.SetValue($Name, [int]$Value, [Microsoft.Win32.RegistryValueKind]::DWord)
    } finally {
        $registryKey.Dispose()
    }
}

function Set-NssmRegistryString {
    param(
        [Parameter(Mandatory = $true)][string]$ServiceName,
        [Parameter(Mandatory = $true)][string]$SubKeyName,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )
    $subKeyPath = 'SYSTEM\CurrentControlSet\Services\' + $ServiceName + '\Parameters\' + $SubKeyName
    $registryKey = [Microsoft.Win32.Registry]::LocalMachine.CreateSubKey($subKeyPath)
    if ($null -eq $registryKey) { throw "NSSM registry key could not be created for service '$ServiceName'." }
    try {
        # NSSM stores AppExit actions as REG_EXPAND_SZ values.
        $registryKey.SetValue($Name, $Value, [Microsoft.Win32.RegistryValueKind]::ExpandString)
    } finally {
        $registryKey.Dispose()
    }
}

$operationId = [guid]::NewGuid().ToString('N')
$stagingRoot = Join-Path $appRoot ('.gplink-staging-' + $operationId)
$backupRoot = Join-Path $appRoot ('.gplink-backup-' + $operationId)
$stagedRuntimeRoot = Join-Path $stagingRoot 'runtime\java'
$stagedJarPath = Join-Path $stagingRoot 'application.jar'
$stagedNssmPath = Join-Path $stagingRoot 'nssm.exe'
$stagedConfigPath = Join-Path $stagingRoot 'config\application-deploy.yml'
$swapSpecs = @()
if ($useBundledJava) {
    $swapSpecs += [pscustomobject]@{ Source = $stagedRuntimeRoot; Target = $runtimeRoot; Backup = (Join-Path $backupRoot 'runtime\java') }
}
$swapSpecs += @(
    [pscustomobject]@{ Source = $stagedJarPath; Target = $jarPath; Backup = (Join-Path $backupRoot 'application.jar') },
    [pscustomobject]@{ Source = $stagedNssmPath; Target = $nssmPath; Backup = (Join-Path $backupRoot 'nssm.exe') },
    [pscustomobject]@{ Source = $stagedConfigPath; Target = $externalConfigPath; Backup = (Join-Path $backupRoot 'config\application-deploy.yml') }
)
$swapStarted = $false
$restoreFailed = $false

try {
    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    Set-RestrictedAcl -Path $stagingRoot -Directory -ExcludeLocalService
    Set-RestrictedAcl -Path $backupRoot -Directory -ExcludeLocalService

    if ($useBundledJava) {
        $javaArchive = Join-Path $context.PackageDirectory ([string]$javaPackage.file)
        $javaExtractRoot = Join-Path $stagingRoot 'java-extract'
        New-Item -ItemType Directory -Path $javaExtractRoot -Force | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($javaArchive, $javaExtractRoot)
        $javaExeSource = Get-ChildItem -LiteralPath $javaExtractRoot -Filter java.exe -Recurse | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
        if ($null -eq $javaExeSource) { throw 'JRE archive does not contain bin\java.exe.' }
        $jreSourceRoot = Split-Path -Parent (Split-Path -Parent $javaExeSource.FullName)
        New-Item -ItemType Directory -Path $stagedRuntimeRoot -Force | Out-Null
        Copy-Item -Path (Join-Path $jreSourceRoot '*') -Destination $stagedRuntimeRoot -Recurse -Force
        Remove-Item -LiteralPath $javaExtractRoot -Recurse -Force
        Write-DeployLog -Message "No usable system JDK 17 was found; the verified bundled Java runtime $($javaPackage.version) will be installed."
    } else {
        Write-DeployLog -Message "Using existing system JDK $($systemJdk.Version) at $($systemJdk.JavaPath); bundled Java extraction is skipped."
    }

    Copy-Item -LiteralPath (Join-Path $context.PackageDirectory ([string]$applicationPackage.file)) -Destination $stagedJarPath -Force
    Copy-Item -LiteralPath (Join-Path $context.PackageDirectory ([string]$nssmPackage.file)) -Destination $stagedNssmPath -Force
    Write-Utf8NoBomFile -Path $stagedConfigPath -Content (($yamlLines -join [Environment]::NewLine) + [Environment]::NewLine)
    if ((Get-FileHash -LiteralPath $stagedJarPath -Algorithm SHA256).Hash -ne ([string]$applicationPackage.sha256).ToUpperInvariant()) { throw 'Staged application.jar hash mismatch.' }
    if ((Get-FileHash -LiteralPath $stagedNssmPath -Algorithm SHA256).Hash -ne ([string]$nssmPackage.sha256).ToUpperInvariant()) { throw 'Staged nssm.exe hash mismatch.' }
    if ($useBundledJava -and -not (Test-Path -LiteralPath (Join-Path $stagedRuntimeRoot 'bin\java.exe') -PathType Leaf)) { throw 'Staged Java runtime is incomplete.' }

    $swapStarted = $true
    foreach ($spec in $swapSpecs) {
        $targetParent = Split-Path -Parent $spec.Target
        if (-not (Test-Path -LiteralPath $targetParent)) { New-Item -ItemType Directory -Path $targetParent -Force | Out-Null }
        if (Test-Path -LiteralPath $spec.Target) {
            $backupParent = Split-Path -Parent $spec.Backup
            if (-not (Test-Path -LiteralPath $backupParent)) { New-Item -ItemType Directory -Path $backupParent -Force | Out-Null }
            Move-Item -LiteralPath $spec.Target -Destination $spec.Backup
        }
        Move-Item -LiteralPath $spec.Source -Destination $spec.Target
    }
    New-Item -ItemType Directory -Path $serviceLogDirectory -Force | Out-Null
    Set-RestrictedAcl -Path $appRoot -Directory
    foreach ($directory in @($dataDirectory, $logDirectory, $serviceLogDirectory)) {
        & icacls.exe $directory '/inheritance:r' | Out-Null
        & icacls.exe $directory '/grant:r' '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)M' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to set application data ACLs on $directory" }
    }
    Set-RestrictedAcl -Path $configDirectory -Directory
    Set-RestrictedAcl -Path $externalConfigPath

    $javaExe = if ($useBundledJava) { Join-Path $runtimeRoot 'bin\java.exe' } else { [string]$systemJdk.JavaPath }
    if (-not $serviceAlreadyExists) { Invoke-Nssm -Arguments @('install', $serviceName, $javaExe) -Description 'Installing GPLink2 NSSM service' }
    $jvmProfile = Get-ResolvedJvmProfile -Config $config
    $appParameters = Get-GpLinkServiceAppParameters -Config $config -JarPath $jarPath -ExternalConfigPath $externalConfigPath -JvmProfile $jvmProfile
    $stdoutPath = Join-Path $serviceLogDirectory 'stdout.log'
    $stderrPath = Join-Path $serviceLogDirectory 'stderr.log'
    $settings = @(
        @('Application', $javaExe), @('AppDirectory', $appRoot), @('AppParameters', $appParameters),
        @('AppStdout', $stdoutPath), @('AppStderr', $stderrPath), @('AppRotateFiles', '1'),
        @('AppRotateOnline', '1'), @('AppRotateSeconds', '86400'), @('AppRotateBytes', '10485760'),
        @('AppExit', 'Default', 'Restart'), @('AppRestartDelay', '10000'),
        @('AppThrottle', '1500'), @('AppNoConsole', '1'), @('DependOnService', [string]$config.PgServiceName),
        @('ObjectName', 'NT AUTHORITY\LocalService')
    )
    foreach ($setting in $settings) { Invoke-Nssm -Arguments (@('set', $serviceName) + $setting) -Description ("Setting NSSM $($setting[0])") }
    $managedDwordSettings = [ordered]@{
        AppRotateFiles = 1
        AppRotateOnline = 1
        AppRotateSeconds = 86400
        AppRotateBytes = 10485760
        AppRestartDelay = 10000
        AppThrottle = 1500
        AppNoConsole = 1
    }
    foreach ($entry in $managedDwordSettings.GetEnumerator()) {
        Set-NssmRegistryDword -ServiceName $serviceName -Name ([string]$entry.Key) -Value ([int]$entry.Value)
    }
    Set-NssmRegistryString -ServiceName $serviceName -SubKeyName 'AppExit' -Name 'Default' -Value 'Restart'
    & sc.exe config $serviceName 'start=' 'delayed-auto' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure delayed automatic startup.' }

    $firewallRuleName = Get-GpLinkFirewallRuleName -Port ([int]$config.ServerPort)
    $rule = Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue
    if ($null -ne $rule) {
        if (-not (Test-GpLinkFirewallRuleOwned -Rule $rule -BundleId ([string]$context.Manifest.bundleId) -StatePath $context.StatePath)) {
            throw "Existing firewall rule '$firewallRuleName' is not owned by this bundle."
        }
        Remove-NetFirewallRule -Name $firewallRuleName
    }
    if ([bool]$config.OpenApiFirewall) {
        New-NetFirewallRule -Name $firewallRuleName -DisplayName "GPLink2 API $($config.ServerPort)" -Description (Get-GpLinkFirewallRuleDescription -BundleId ([string]$context.Manifest.bundleId)) -Group 'GPLink2 Offline Deployment' -Direction Inbound -Action Allow -Protocol TCP -LocalPort ([int]$config.ServerPort) -Profile ([string[]]$config.FirewallProfiles) -RemoteAddress Any -Enabled True | Out-Null
    }

    Start-Service -Name $serviceName
    Wait-ServiceRunning -Name $serviceName -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)
    $javaRuntimeMode = if ($useBundledJava) { 'BundledJre' } else { 'SystemJdk' }
    $javaRuntimeVersion = if ($useBundledJava) { ([string]$javaPackage.version -split '\+')[0] } else { [string]$systemJdk.Version }
    Write-DeployLog -Message "GPLink2 service is running with JVM profile $jvmProfile and $javaRuntimeMode Java $javaRuntimeVersion."
} catch {
    if ($serviceAlreadyExists -and $swapStarted) {
        try {
            Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
            foreach ($spec in $swapSpecs) {
                if (Test-Path -LiteralPath $spec.Target) { Remove-Item -LiteralPath $spec.Target -Recurse -Force }
                if (Test-Path -LiteralPath $spec.Backup) {
                    $targetParent = Split-Path -Parent $spec.Target
                    if (-not (Test-Path -LiteralPath $targetParent)) { New-Item -ItemType Directory -Path $targetParent -Force | Out-Null }
                    Move-Item -LiteralPath $spec.Backup -Destination $spec.Target
                }
            }
            Write-DeployLog -Level 'WARN' -Message 'Restored the previous GPLink2 Java runtime (when managed by the bundle), application JAR, NSSM, and external configuration after repair failure.'
        } catch {
            $restoreFailed = $true
            Write-DeployLog -Level 'WARN' -Message "Automatic application-file restore failed; backup retained at $backupRoot."
        }
    }
    throw
} finally {
    if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
    if (-not $restoreFailed -and (Test-Path -LiteralPath $backupRoot)) { Remove-Item -LiteralPath $backupRoot -Recurse -Force }
}

[pscustomobject]@{
    ServiceName = $serviceName
    FirewallRuleName = $firewallRuleName
    JvmProfile = $jvmProfile
    JavaPath = $javaExe
    JavaRuntimeMode = $javaRuntimeMode
    JavaRuntimeVersion = $javaRuntimeVersion
    ApplicationPath = $jarPath
    ExternalConfigPath = $externalConfigPath
    StdoutPath = $stdoutPath
    StderrPath = $stderrPath
}
