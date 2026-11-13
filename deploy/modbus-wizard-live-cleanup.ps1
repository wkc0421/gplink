[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ManifestPath,[switch]$Execute)
$ErrorActionPreference='Stop'
$helper='C:\Users\wkc\.codex\skills\test-gplink2-test-api\scripts\invoke-gplink2-test-api.ps1'
$m=Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8|ConvertFrom-Json
$p=[string]$m.prefix
if($p -notmatch '^codex_ui_\d{8}_\d{6}_[0-9a-f]{8}$'){throw 'Invalid test prefix'}
if($m.frontend -cne 'http://192.168.5.19:18088' -or [int]$m.port -ne 31083){throw 'Unexpected test environment'}
if($m.network -notmatch '^\d{15,20}$' -or $m.gateway -notmatch '^\d{15,20}$'){throw 'Invalid network or gateway ID'}
if($m.networkName -cne ($p+'_network') -or $m.gatewayName -cne ($p+'_access')){throw 'Invalid network or gateway name'}
if($m.parent -cne ($p+'_parent') -or $m.parentProduct -cne ($p+'_gp') -or $m.childProduct -cne ($p+'_cp')){throw 'Invalid product or parent ID'}
$children=@(1..4|ForEach-Object{$p+'_c'+$_})
if(@($m.children).Count -ne 4){throw 'Invalid child count'}
for($i=0;$i -lt 4;$i++){if($m.children[$i] -cne $children[$i]){throw 'Invalid child ID'}}
$all=@($m.parentProduct,$m.childProduct,$m.parent)+$children
if(@($m.created).Count -ne 7){throw 'Manifest has unexpected created count'}
foreach($id in $all){if(@($m.created|Where-Object {$_.id -ceq $id}).Count -ne 1){throw "Missing or duplicate manifest resource: $id"}}
Write-Host "CLEANUP_PLAN prefix=$p network=$($m.network) gateway=$($m.gateway) port=$($m.port) children=$($children -join ',')"
Write-Host 'Verify IDs, names, product/access/network dependencies; stop gateway/network; delete children then parent, products, gateway, network; verify 404 and port closed.'
if(-not $Execute){Write-Host 'PLAN ONLY';return}
$evidence=Join-Path (Split-Path -Parent $ManifestPath) 'cleanup-evidence.txt'
function Evidence([string]$Text){Write-Host $Text;Add-Content -LiteralPath $evidence -Value $Text -Encoding UTF8}
function Api([string]$Method,[string]$Path){
    $params=@{Method=$Method;Path=$Path;TimeoutSeconds=90}
    if($Method -ne 'GET'){$params.AllowMutation=$true}
    $v=@(& $helper @params);$http=$v|Where-Object {$_ -like 'HTTP_STATUS:*'}|Select-Object -First 1;$i=[array]::IndexOf($v,'RESPONSE:')
    if($http -ne 'HTTP_STATUS: 200' -or $i -lt 0){throw "API failure $Method $Path : $http"}
    $r=$v[$i+1]|ConvertFrom-Json
    if($r.status -ne 200 -or $r.success -eq $false -or $r.result.success -eq $false){throw "API failure $Method $Path : $($r.message)"}
    if($Method -eq 'DELETE' -and ($r.result -eq 0 -or $r.result -eq $false)){throw "Nothing deleted $Path"}
    Evidence "PASS $Method $Path HTTP=200"
    return $r.result
}
function Absent([string]$Path){try{$null=Api GET $Path;throw "Still present $Path"}catch{if($_.Exception.Message -ne 'API request failed with HTTP 404.'){throw};Evidence "PASS GET $Path HTTP=404"}}
function Own([string]$Path,[string]$Id,[string]$Name){$r=Api GET $Path;if($r.id -cne $Id -or $r.name -cne $Name){throw "Resource ID/name mismatch $Path"};return $r}
function NameOf([string]$Id){return [string](@($m.created|Where-Object {$_.id -ceq $Id})[0].name)}
try {
    $net=Own "/network/config/$($m.network)" $m.network $m.networkName
    if($net.type -cne 'TCP_SERVER' -or [int]$net.configuration.port -ne [int]$m.port){throw 'Network type or port changed'}
    $gw=Own "/gateway/device/$($m.gateway)" $m.gateway $m.gatewayName
    if($gw.channelId -cne $m.network -or $gw.configuration.deviceId -cne $m.parent){throw 'Gateway network or parent changed'}
    $actualChildren=@($gw.configuration.childDeviceIds)
    if($actualChildren.Count -ne 4 -or @($children|Where-Object {$_ -cnotin $actualChildren}).Count -ne 0){throw 'Gateway child list changed'}
    foreach($id in @($m.parentProduct,$m.childProduct)){$prod=Own "/device-product/$id" $id (NameOf $id);if($prod.accessId -cne $m.gateway){throw "Product access changed: $id"}}
    $parent=Own "/device-instance/$($m.parent)" $m.parent (NameOf $m.parent)
    if($parent.productId -cne $m.parentProduct){throw 'Parent product changed'}
    foreach($id in $children){$child=Own "/device-instance/$id" $id (NameOf $id);if($child.parentId -cne $m.parent -or $child.productId -cne $m.childProduct){throw "Child relationship changed: $id"}}
    $null=Api POST "/gateway/device/$($m.gateway)/_shutdown"
    $null=Api POST "/network/config/$($m.network)/_shutdown"
    foreach($id in $children+@($m.parent)){$null=Api POST "/device-instance/$id/undeploy";$null=Api DELETE "/device-instance/$id";Absent "/device-instance/$id"}
    foreach($id in @($m.childProduct,$m.parentProduct)){$null=Api POST "/device-product/$id/undeploy";$null=Api DELETE "/device-product/$id";Absent "/device-product/$id"}
    $null=Api DELETE "/gateway/device/$($m.gateway)";Absent "/gateway/device/$($m.gateway)"
    $null=Api DELETE "/network/config/$($m.network)";Absent "/network/config/$($m.network)"
    $credential=Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
    $count=Invoke-Command -ComputerName '192.168.5.19' -Credential $credential -ScriptBlock {param($port);@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count} -ArgumentList ([int]$m.port)
    if([int]$count -ne 0){throw "Port $($m.port) still listening"}
    Evidence "PASS remote port=$($m.port) listeners=0"
    Evidence 'CLEANUP_COMPLETE'
}catch{Evidence "CLEANUP_STOPPED error=$($_.Exception.Message)";throw}
