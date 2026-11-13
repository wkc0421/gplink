[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ManifestPath,
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
$helper = 'C:\Users\wkc\.codex\skills\test-gplink2-test-api\scripts\invoke-gplink2-test-api.ps1'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$prefix = [string]$manifest.prefix
if ($prefix -notmatch '^codex_mb_\d{8}_\d{6}_[0-9a-f]{8}$') { throw 'Invalid isolated test prefix' }
foreach ($pair in @(@('network','_net'),@('gateway','_access'),@('parent','_parent'),@('parentProduct','_gp'),@('childProduct','_cp'))) {
    if ([string]$manifest.($pair[0]) -cne ($prefix + $pair[1])) { throw "Invalid resource ID: $($pair[0])" }
}
$expectedChildren = @(1..5 | ForEach-Object { $prefix + '_c' + $_ })
if (@($manifest.children).Count -ne 5) { throw 'Invalid child count' }
for ($i=0; $i -lt 5; $i++) { if ([string]$manifest.children[$i] -cne $expectedChildren[$i]) { throw 'Invalid child ID' } }
if ($manifest.port -lt 1024 -or $manifest.port -gt 65535) { throw 'Invalid port' }

Write-Host "CLEANUP_PLAN prefix=$prefix port=$($manifest.port) children=$($expectedChildren -join ',')"
Write-Host 'Sequence: check exact records; stop gateway and network; undeploy/delete children then parent; undeploy/delete products; delete gateway then network; verify HTTP 404 and no listening socket.'
if (-not $Execute) { Write-Host 'PLAN ONLY: no API or external mutations'; return }

$evidencePath = Join-Path (Split-Path -Parent $ManifestPath) 'cleanup-evidence.txt'
function Evidence([string]$Text) { Write-Host $Text; Add-Content -LiteralPath $evidencePath -Value $Text -Encoding UTF8 }
function Api([string]$Method, [string]$Path, $Body=$null) {
    $requestParameters = @{Method=$Method;Path=$Path;TimeoutSeconds=90}
    if ($Method -ne 'GET') { $requestParameters.AllowMutation=$true }
    if ($null -ne $Body) { $requestParameters.BodyJson=ConvertTo-Json -InputObject $Body -Depth 20 -Compress }
    $lines = @(& $helper @requestParameters)
    $http = $lines | Where-Object { $_ -like 'HTTP_STATUS:*' } | Select-Object -First 1
    $marker = [array]::IndexOf($lines,'RESPONSE:')
    if ($http -ne 'HTTP_STATUS: 200' -or $marker -lt 0) { throw "Unexpected response $Method $Path : $http" }
    $response = $lines[$marker+1] | ConvertFrom-Json
    if ($response.status -ne 200 -or $response.success -eq $false -or $response.result.success -eq $false) { throw "API failure $Method $Path : $($response.message)" }
    Evidence "PASS $Method $Path HTTP=200"
    return $response.result
}
function VerifyAbsent([string]$Path) {
    try { $null=Api GET $Path; throw "Still present: $Path" }
    catch { if ($_.Exception.Message -ne 'API request failed with HTTP 404.') { throw }; Evidence "PASS GET $Path HTTP=404" }
}
function VerifyOwn([string]$Path,[string]$Id) {
    $record=Api GET $Path
    if ($record.id -cne $Id -or $record.name -cne $Id) { throw "Refusing operation: ID/name mismatch for $Path" }
}

try {
    VerifyOwn "/network/config/$($manifest.network)" $manifest.network
    VerifyOwn "/gateway/device/$($manifest.gateway)" $manifest.gateway
    VerifyOwn "/device-product/$($manifest.parentProduct)" $manifest.parentProduct
    VerifyOwn "/device-product/$($manifest.childProduct)" $manifest.childProduct
    VerifyOwn "/device-instance/$($manifest.parent)" $manifest.parent
    foreach ($child in $expectedChildren) { VerifyOwn "/device-instance/$child" $child }
    $null=Api POST "/gateway/device/$($manifest.gateway)/_shutdown"
    $null=Api POST "/network/config/$($manifest.network)/_shutdown"
    foreach ($id in @($expectedChildren)+@($manifest.parent)) {
        $null=Api POST "/device-instance/$id/undeploy"
        $null=Api DELETE "/device-instance/$id"
        VerifyAbsent "/device-instance/$id"
    }
    foreach ($id in @($manifest.childProduct,$manifest.parentProduct)) {
        $null=Api POST "/device-product/$id/undeploy"
        $null=Api DELETE "/device-product/$id"
        VerifyAbsent "/device-product/$id"
    }
    $null=Api DELETE "/gateway/device/$($manifest.gateway)"
    VerifyAbsent "/gateway/device/$($manifest.gateway)"
    $null=Api DELETE "/network/config/$($manifest.network)"
    VerifyAbsent "/network/config/$($manifest.network)"
    $credentialPath=Join-Path $env:TEMP 'gplink2-remote.credential.xml'
    $credential=Import-Clixml -LiteralPath $credentialPath
    $listening=Invoke-Command -ComputerName '192.168.5.19' -Credential $credential -ScriptBlock {
        param($port)
        @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count
    } -ArgumentList ([int]$manifest.port)
    if ([int]$listening -ne 0) { throw "Port $($manifest.port) is still listening" }
    Evidence "PASS remote port=$($manifest.port) listeners=0"
    Evidence 'CLEANUP_COMPLETE'
} catch {
    Evidence "CLEANUP_STOPPED error=$($_.Exception.Message)"
    throw
}
