[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Bind')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$ManifestPath,
    [switch]$Execute
)
$ErrorActionPreference='Stop'
$helper='C:\Users\wkc\.codex\skills\test-gplink2-test-api\scripts\invoke-gplink2-test-api.ps1'
$m=Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$prefix=[string]$m.prefix
if($prefix -notmatch '^codex_mb_\d{8}_\d{6}_[0-9a-f]{8}$' -or $m.parent -cne ($prefix+'_parent') -or $m.childProduct -cne ($prefix+'_cp')) { throw 'Invalid isolated test identity' }
$child=$prefix+'_c5'
Write-Host "PLAN mode=$Mode child=$child parent=$($m.parent)"
if(-not $Execute) { Write-Host 'PLAN ONLY'; return }
function Api([string]$Method,[string]$Path,$Body=$null) {
    $parameters=@{Method=$Method;Path=$Path;TimeoutSeconds=90}
    if($Method -ne 'GET') {$parameters.AllowMutation=$true}
    if($null -ne $Body) {$parameters.BodyJson=ConvertTo-Json -InputObject $Body -Depth 30 -Compress}
    $lines=@(& $helper @parameters)
    $http=$lines | Where-Object {$_ -like 'HTTP_STATUS:*'} | Select-Object -First 1
    $marker=[array]::IndexOf($lines,'RESPONSE:')
    if($http -ne 'HTTP_STATUS: 200' -or $marker -lt 0){throw "API failed $Method $Path : $http"}
    $result=$lines[$marker+1] | ConvertFrom-Json
    if($result.status -ne 200 -or $result.success -eq $false -or $result.result.success -eq $false){throw "API failed $Method $Path : $($result.message) $($result.result.message)"}
    Write-Host "PASS $Method $Path HTTP=200 status=$($result.status)"
    return $result.result
}
function Save { $m | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8 }
if($Mode -eq 'Prepare') {
    if(@($m.children).Count -ne 4 -or $m.children -contains $child){throw 'Unexpected manifest children'}
    $exists=Api GET "/device-instance/$child/exists"
    if($exists -ne $false){throw 'Fifth child ID already exists or check was inconclusive'}
    $m.children=@($m.children)+@($child)
    $m.completed=@($m.completed)+@('child5-planned')
    Save
    $null=Api POST '/device-instance' @{id=$child;name=$child;productId=$m.childProduct;configuration=@{slaveId=5;pollOverrideEnabled=$false}}
    $m.completed=@($m.completed)+@('child5-created'); Save
    $null=Api POST "/device-instance/$child/deploy"
    $m.completed=@($m.completed)+@('child5-deployed'); Save
    $detail=Api GET "/device-instance/$child/detail"
    if($detail.parentId){throw "Unexpected parent before binding: $($detail.parentId)"}
    $state=if($detail.state.value){$detail.state.value}else{$detail.state}
    if($state -notin @('offline','online')){throw "Child is not activated: $state"}
    Write-Host "PRE_BIND_READY child=$child parent=null state=$state; independently check Redis before running Bind"
    return
}
if(@($m.children).Count -ne 5 -or $m.children[4] -cne $child){throw 'Fifth child is not in manifest'}
$null=Api POST "/device/gateway/$($m.parent)/bind?validateRuntime=true" @($child)
$m.completed=@($m.completed)+@('child5-strict-bind'); Save
$detail=Api GET "/device-instance/$child/detail"
if($detail.parentId -cne $m.parent){throw "Wrong parent after binding: $($detail.parentId)"}
Write-Host "POST_BIND_READY child=$child parent=$($detail.parentId); independently verify Redis"
