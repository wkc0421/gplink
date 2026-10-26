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

$baselinePackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'baseline-sql'
$baselinePath = Join-Path $context.PackageDirectory ([string]$baselinePackage.file)
$baselineHash = ([string]$baselinePackage.sha256).ToUpperInvariant()
$bundleId = [string]$context.Manifest.bundleId
$psql = Join-Path ([string]$config.PgInstallRoot) 'bin\psql.exe'
$database = [string]$config.DatabaseName
$port = [int]$config.PgPort
$superUser = [string]$config.PostgresSuperUser
$superPassword = [string]$config.PostgresSuperPassword
$schema = [string]$config.DatabaseSchema
$schemaIdentifier = Quote-PgIdentifier -Value $schema
$serviceName = [string]$config.AppServiceName
$timeoutSeconds = [Math]::Max([int]$config.ServiceStartTimeoutSeconds, [int]$config.ApiVerifyTimeoutSeconds)

if ($bundleId -notmatch '^[A-Za-z0-9._-]{1,128}$') { throw 'Bundle ID is not safe for the baseline marker.' }
if ($baselineHash -notmatch '^[A-F0-9]{64}$') { throw 'Baseline SQL hash is not finalized.' }
Test-BaselineSql -Path $baselinePath

Wait-ServiceRunning -Name ([string]$config.PgServiceName) -TimeoutSeconds $timeoutSeconds
Wait-ServiceRunning -Name $serviceName -TimeoutSeconds $timeoutSeconds
Wait-TcpPort -HostName '127.0.0.1' -Port ([int]$config.ServerPort) -TimeoutSeconds $timeoutSeconds

$requiredTables = @('dev_product', 'dev_device_instance', 'device_gateway', 'dev_protocol', 'network_config', 's_file')
$tableChecks = $requiredTables | ForEach-Object {
    $qualified = Quote-PgLiteral -Value ($schema + '.' + $_)
    "to_regclass($qualified) IS NOT NULL"
}
$schemaDeadline = (Get-Date).AddSeconds($timeoutSeconds)
do {
    $ready = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql ("SELECT CASE WHEN " + ($tableChecks -join ' AND ') + " THEN '1' ELSE '0' END;")) -join '').Trim()
    if ($ready -eq '1') { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $schemaDeadline)
if ($ready -ne '1') { throw 'GPLink2 did not finish creating the required database tables before the baseline timeout.' }

$markerQualifiedLiteral = Quote-PgLiteral -Value ($schema + '.gplink_deployment_baseline')
$markerExists = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT CASE WHEN to_regclass($markerQualifiedLiteral) IS NULL THEN '0' ELSE '1' END;") -join '').Trim()
if ($markerExists -eq '1') {
    $marker = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT bundle_id || '|' || sql_sha256 FROM $schemaIdentifier.gplink_deployment_baseline ORDER BY applied_at DESC LIMIT 1;") -join '').Trim()
    if ($marker -eq ($bundleId + '|' + $baselineHash)) {
        Write-DeployLog -Message 'The matching one-time GPLink2 baseline marker already exists; repair mode will not clear application data again.'
        return [pscustomobject]@{ Applied = $false; BundleId = $bundleId; SqlSha256 = $baselineHash }
    }
    throw 'A different GPLink2 baseline marker already exists. Automatic replacement or repeated cleanup is not allowed.'
}

Write-DeployLog -Message 'Stopping GPLink2 to apply the one-time baseline transaction.'
$serviceStopped = $false
try {
    if ((Get-Service -Name $serviceName -ErrorAction Stop).Status -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds($timeoutSeconds))
    }
    $serviceStopped = $true

    try {
        [void](Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -File $baselinePath -Variables @{
            database_schema = $schema
            bundle_id = $bundleId
            baseline_sha256 = $baselineHash
        })
    } catch {
        throw 'The one-time baseline transaction failed. Detailed psql text was suppressed because the SQL contains retained network credentials.'
    }

    $actual = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql @"
SELECT
    (SELECT count(*) FROM $schemaIdentifier.dev_product) || '|' ||
    (SELECT count(*) FROM $schemaIdentifier.dev_device_instance) || '|' ||
    (SELECT count(*) FROM $schemaIdentifier.device_gateway) || '|' ||
    COALESCE((SELECT string_agg(id, ',' ORDER BY id) FROM $schemaIdentifier.dev_protocol), '') || '|' ||
    COALESCE((SELECT string_agg(id, ',' ORDER BY id) FROM $schemaIdentifier.network_config), '');
"@) -join '').Trim()
    $expected = '0|0|0||2068926208973430784,2068932624811515904'
    if ($actual -ne $expected) { throw 'Post-import baseline verification did not match the approved resource set (no protocols, two network components).' }

    $marker = ((Invoke-Psql -PsqlPath $psql -Password $superPassword -User $superUser -Database $database -Port $port -Sql "SELECT bundle_id || '|' || sql_sha256 FROM $schemaIdentifier.gplink_deployment_baseline WHERE bundle_id=$(Quote-PgLiteral -Value $bundleId);") -join '').Trim()
    if ($marker -ne ($bundleId + '|' + $baselineHash)) { throw 'The baseline transaction completed without the expected marker.' }
    Write-DeployLog -Message 'The one-time GPLink2 baseline transaction completed and was verified.'
} finally {
    if ($serviceStopped -and (Get-Service -Name $serviceName -ErrorAction SilentlyContinue).Status -ne 'Running') {
        Start-Service -Name $serviceName
        Wait-ServiceRunning -Name $serviceName -TimeoutSeconds $timeoutSeconds
        Wait-TcpPort -HostName '127.0.0.1' -Port ([int]$config.ServerPort) -TimeoutSeconds $timeoutSeconds
    }
}

[pscustomobject]@{
    Applied = $true
    BundleId = $bundleId
    SqlSha256 = $baselineHash
    AppliedAt = (Get-Date).ToUniversalTime().ToString('o')
}
