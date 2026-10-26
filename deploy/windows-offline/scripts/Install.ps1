[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$context = Get-DeploymentContext -ConfigPath $ConfigPath
$config = $context.Config
$logDirectory = Join-Path $context.BundleRoot 'logs'
$installLog = Join-Path $logDirectory ('install-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
Initialize-DeployLog -LogPath $installLog -Config $config
$env:GPLINK_DEPLOY_LOG_PATH = $installLog

if (-not (Test-IsAdministrator)) { throw 'Install.ps1 must run as administrator.' }

$powerShellExe = Join-Path $PSHOME 'powershell.exe'
Write-DeployLog -Message 'Re-running the complete read-only preflight before making any changes.'
& $powerShellExe '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' (Join-Path $PSScriptRoot 'Preflight.ps1') '-ConfigPath' ([System.IO.Path]::GetFullPath($ConfigPath)) '-Quiet'
if ($LASTEXITCODE -ne 0) { throw "Preflight failed. Review $logDirectory\preflight-report.txt before retrying." }

$applicationPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'application'
$timescalePackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'timescaledb'
$postgresPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'postgresql'
$baselinePackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
$appServiceExisted = $null -ne (Get-ServiceOrNull -Name ([string]$config.AppServiceName))
$firewallRuleName = Get-GpLinkFirewallRuleName -Port ([int]$config.ServerPort)
$firewallExisted = $null -ne (Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue)
$configPathFull = [System.IO.Path]::GetFullPath($context.ConfigPath)
Set-RestrictedAcl -Path $configPathFull -ExcludeLocalService
$baselinePath = Join-Path $context.PackageDirectory ([string]$baselinePackage.file)
Set-RestrictedAcl -Path $baselinePath -ExcludeLocalService
Write-DeployLog -Message 'Restricted the source deployment configuration and baseline SQL to Administrators and SYSTEM.'
$stateDirectory = Split-Path -Parent $context.StatePath
New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
Set-RestrictedAcl -Path $stateDirectory -Directory

$packageState = @()
foreach ($package in $context.Manifest.packages) {
    $packageState += [ordered]@{
        id = [string]$package.id
        file = [string]$package.file
        version = [string]$package.version
        sha256 = ([string]$package.sha256).ToUpperInvariant()
        size = [long]$package.size
    }
}
$state = [ordered]@{
    schemaVersion = 2
    status = 'Installing'
    bundleId = [string]$context.Manifest.bundleId
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
    updatedAt = (Get-Date).ToUniversalTime().ToString('o')
    appServiceName = [string]$config.AppServiceName
    pgServiceName = [string]$config.PgServiceName
    appRoot = [System.IO.Path]::GetFullPath([string]$config.AppRoot)
    pgInstallRoot = [System.IO.Path]::GetFullPath([string]$config.PgInstallRoot)
    pgData = [System.IO.Path]::GetFullPath([string]$config.PgData)
    serverPort = [int]$config.ServerPort
    pgPort = [int]$config.PgPort
    databaseName = [string]$config.DatabaseName
    databaseUser = [string]$config.DatabaseUser
    applicationSha256 = ([string]$applicationPackage.sha256).ToUpperInvariant()
    postgresqlVersion = [string]$postgresPackage.version
    timescaleVersion = [string]$timescalePackage.version
    baselineSqlSha256 = ([string]$baselinePackage.sha256).ToUpperInvariant()
    packages = $packageState
    rebootVerificationRequired = $true
}
Write-JsonFile -InputObject $state -Path $context.StatePath -Depth 8
Set-RestrictedAcl -Path $context.StatePath

try {
    $vcPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'vcredist'
    $vcPath = Join-Path $context.PackageDirectory ([string]$vcPackage.file)
    [void](Invoke-NativeProcess -FilePath $vcPath -ArgumentList ([string[]]$config.VcRuntimeArguments) -Description 'Installing Microsoft Visual C++ x64 runtime' -SuccessExitCodes @(0, 1638, 3010))

    $openSslPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'openssl'
    $openSslPath = Join-Path $context.PackageDirectory ([string]$openSslPackage.file)
    $openSslArguments = @([string[]]$config.OpenSslArguments) + ('APPDIR="' + [string]$config.OpenSslInstallRoot + '"')
    [void](Invoke-NativeProcess -FilePath $openSslPath -ArgumentList $openSslArguments -Description 'Installing OpenSSL 3 x64 runtime' -SuccessExitCodes @(0, 3010))

    Write-DeployLog -Message 'Installing or validating PostgreSQL.'
    $pgResult = & (Join-Path $PSScriptRoot 'Install-PostgreSql.ps1') -ConfigPath $context.ConfigPath

    Write-DeployLog -Message 'Installing or validating TimescaleDB and database roles.'
    $timescaleResult = & (Join-Path $PSScriptRoot 'Install-TimescaleDb.ps1') -ConfigPath $context.ConfigPath

    Write-DeployLog -Message 'Installing or repairing the GPLink2 NSSM service.'
    $serviceResult = & (Join-Path $PSScriptRoot 'Install-GpLinkService.ps1') -ConfigPath $context.ConfigPath

    Write-DeployLog -Message 'Applying or validating the one-time GPLink2 resource baseline SQL.'
    $baselineResult = & (Join-Path $PSScriptRoot 'Import-GpLinkBaseline.ps1') -ConfigPath $context.ConfigPath

    $state.status = 'Verifying'
    $state.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
    $state.jvmProfile = [string]$serviceResult.JvmProfile
    $state.javaRuntimeMode = [string]$serviceResult.JavaRuntimeMode
    $state.javaRuntimePath = [System.IO.Path]::GetFullPath([string]$serviceResult.JavaPath)
    $state.javaRuntimeVersion = [string]$serviceResult.JavaRuntimeVersion
    $state.firewallRuleName = [string]$serviceResult.FirewallRuleName
    $state.baselineAppliedByThisRun = [bool]$baselineResult.Applied
    if ($baselineResult.Applied -and $baselineResult.PSObject.Properties.Name -contains 'AppliedAt') { $state.baselineAppliedAt = [string]$baselineResult.AppliedAt }
    Write-JsonFile -InputObject $state -Path $context.StatePath -Depth 8
    Set-RestrictedAcl -Path $context.StatePath

    & $powerShellExe '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' (Join-Path $PSScriptRoot 'Verify.ps1') '-ConfigPath' $context.ConfigPath '-AllowIncompleteState'
    if ($LASTEXITCODE -ne 0) { throw "Post-install verification failed. Review $logDirectory\verify-report.txt." }

    $state.status = 'Complete'
    $state.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
    $state.completedAt = $state.updatedAt
    $state.lastVerifiedAt = $state.updatedAt
    Write-JsonFile -InputObject $state -Path $context.StatePath -Depth 8
    Set-RestrictedAcl -Path $context.StatePath
    Write-DeployLog -Message 'Installation and live verification completed successfully. Run Verify-GpLink.cmd once after the planned Windows reboot.'
    exit 0
} catch {
    $safeError = Protect-LogText -Text $_.Exception.Message
    Write-DeployLog -Level 'ERROR' -Message ("Installation failed: $safeError")
    if (-not $appServiceExisted -and $null -ne (Get-ServiceOrNull -Name ([string]$config.AppServiceName))) {
        try {
            Stop-Service -Name ([string]$config.AppServiceName) -Force -ErrorAction SilentlyContinue
            $managedNssm = Join-Path ([string]$config.AppRoot) 'nssm.exe'
            if (Test-Path -LiteralPath $managedNssm) { & $managedNssm 'remove' ([string]$config.AppServiceName) 'confirm' | Out-Null }
            Write-DeployLog -Level 'WARN' -Message 'Rolled back the GPLink2 service created by this run.'
        } catch { Write-DeployLog -Level 'WARN' -Message 'Could not completely remove the GPLink2 service created by this run.' }
    }
    if ($appServiceExisted -and $null -ne (Get-ServiceOrNull -Name ([string]$config.AppServiceName)) -and (Get-Service -Name ([string]$config.AppServiceName)).Status -ne 'Running') {
        try { Start-Service -Name ([string]$config.AppServiceName); Write-DeployLog -Level 'WARN' -Message 'Attempted to restore the pre-existing GPLink2 service after repair failure.' }
        catch { Write-DeployLog -Level 'WARN' -Message 'The pre-existing GPLink2 service could not be restarted after repair failure.' }
    }
    if (-not $firewallExisted) {
        $rollbackRule = Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue
        if ($null -ne $rollbackRule -and (Test-GpLinkFirewallRuleOwned -Rule $rollbackRule -BundleId ([string]$context.Manifest.bundleId) -StatePath $context.StatePath)) {
            try { Remove-NetFirewallRule -Name $firewallRuleName; Write-DeployLog -Level 'WARN' -Message 'Rolled back the API firewall rule created by this run.' }
            catch { Write-DeployLog -Level 'WARN' -Message 'Could not remove the API firewall rule created by this run.' }
        } elseif ($null -ne $rollbackRule) {
            Write-DeployLog -Level 'WARN' -Message 'A same-name firewall rule without this bundle marker was not removed during rollback.'
        }
    }
    $state.status = 'Failed'
    $state.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
    $state.failedAt = $state.updatedAt
    $state.failure = $safeError
    Write-JsonFile -InputObject $state -Path $context.StatePath -Depth 8
    Set-RestrictedAcl -Path $context.StatePath
    Write-DeployLog -Level 'WARN' -Message 'PostgreSQL and its data directory were intentionally preserved for repair.'
    exit 1
}
