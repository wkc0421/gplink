[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$Port = 31082,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$helper = 'C:\Users\wkc\.codex\skills\test-gplink2-test-api\scripts\invoke-gplink2-test-api.ps1'
$run = 'codex_mb_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [guid]::NewGuid().ToString('N').Substring(0,8)
$network = $run + '_net'
$gateway = $run + '_access'
$parent = $run + '_parent'
$parentProduct = $run + '_gp'
$childProduct = $run + '_cp'
$children = @(1..4 | ForEach-Object { $run + '_c' + $_ })
$folder = Join-Path $env:TEMP $run
$manifestPath = Join-Path $folder 'manifest.json'

if (-not $Execute) {
    Write-Output 'PLAN ONLY: no API or external mutations. Supply -Execute only after deployment and port checks.'
    Write-Output "identifier=$run port=$Port; 1 TCP server, 1 gateway, 2 products, 1 parent and 4 children"
    return
}

New-Item -ItemType Directory -Path $folder -ErrorAction Stop | Out-Null
$manifest = [ordered]@{ prefix=$run; port=$Port; network=$network; gateway=$gateway; parent=$parent; parentProduct=$parentProduct; childProduct=$childProduct; children=$children; completed=@() }
function Save-Manifest { $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding UTF8 }
Save-Manifest

function Invoke-TestApi([string]$Method, [string]$Path, $Body = $null) {
    $parameters = @{ Method=$Method; Path=$Path; TimeoutSeconds=90 }
    if ($Method -ne 'GET') { $parameters.AllowMutation = $true }
    if ($null -ne $Body) { $parameters.BodyJson = ConvertTo-Json -InputObject $Body -Depth 30 -Compress }
    $output = @(& $helper @parameters)
    $http = ($output | Where-Object { $_ -like 'HTTP_STATUS:*' } | Select-Object -First 1)
    $marker = [array]::IndexOf($output, 'RESPONSE:')
    if ($marker -lt 0 -or $marker + 1 -ge $output.Count) { throw "Missing response from $Method $Path" }
    $envelope = $output[$marker+1] | ConvertFrom-Json
    if ($http -ne 'HTTP_STATUS: 200' -or $envelope.status -ne 200 -or $envelope.success -eq $false -or $envelope.result.success -eq $false) {
        throw "Request failed $Method $Path : HTTP=$http status=$($envelope.status) message=$($envelope.message) resultMessage=$($envelope.result.message)"
    }
    Write-Host ("PASS {0} {1} HTTP=200 status={2}" -f $Method, $Path, $envelope.status)
    return $envelope.result
}
function Mark([string]$Name) { $manifest.completed += $Name; Save-Manifest }

try {
    $null = Invoke-TestApi POST '/network/config' @{ id=$network; name=$network; type='TCP_SERVER'; state='disabled'; shareCluster=$true; configuration=@{host='0.0.0.0';port=$Port;publicHost='192.168.5.19';publicPort=$Port;secure=$false;parserType='DIRECT';parserConfiguration=@{};tcpKeepAlive=$true} }
    Mark 'network'
    $null = Invoke-TestApi POST '/gateway/device' @{ id=$gateway; name=$gateway; provider='tcp-server-gateway'; state='disabled'; channel='network';channelId=$network;protocol='modbus-rtu.v1';transport='TCP';configuration=@{deviceId=$parent;childDeviceIds=@()} }
    Mark 'gateway'
    foreach ($spec in @(@{id=$parentProduct; type='gateway'},@{id=$childProduct; type='childrenDevice'})) {
        $null = Invoke-TestApi POST '/device-product' @{id=$spec.id;name=$spec.id;deviceType=$spec.type;messageProtocol='modbus-rtu.v1';protocolName='Modbus RTU';transportProtocol='TCP';accessId=$gateway;accessName=$gateway;accessProvider='tcp-server-gateway';metadata='{"properties":[],"events":[],"functions":[],"tags":[]}';configuration=@{responseTimeoutMs=3000;probeIntervalMs=30000;keepOnlineTimeout=120;pollEnabled=$false;registerMap='[]'}}
        Mark ('product:'+ $spec.id)
        $null = Invoke-TestApi POST "/device-product/$($spec.id)/deploy"
        Mark ('product-deploy:'+ $spec.id)
    }
    $null = Invoke-TestApi POST '/device-instance' @{id=$parent;name=$parent;productId=$parentProduct}
    Mark 'parent'
    $null = Invoke-TestApi POST "/device-instance/$parent/deploy"
    Mark 'parent-deploy'
    for ($index = 0; $index -lt $children.Count; $index++) {
        $child = $children[$index]
        $null = Invoke-TestApi POST '/device-instance' @{id=$child;name=$child;productId=$childProduct;parentId=$parent;configuration=@{slaveId=($index+1);pollOverrideEnabled=$false}}
        Mark ('child:'+ $child)
        $null = Invoke-TestApi POST "/device-instance/$child/deploy"
        Mark ('child-deploy:'+ $child)
    }
    $null = Invoke-TestApi POST "/device/gateway/$parent/bind?validateRuntime=true" $children
    Mark 'strict-bind'
    foreach ($child in $children) {
        $actual = Invoke-TestApi GET "/device-instance/$child/detail"
        if ($actual.parentId -ne $parent) { throw "Incorrect parent for $child" }
        $state = if ($actual.state.value) { $actual.state.value } else { $actual.state }
        if ($state -notin @('online','offline')) { throw "Device $child is not activated: $state" }
        Write-Host "PASS child=$child parent=$($actual.parentId) state=$state"
    }
    $gatewayDetail = Invoke-TestApi GET "/gateway/device/$gateway"
    $gatewayDetail.configuration.deviceId = $parent
    $gatewayDetail.configuration | Add-Member -NotePropertyName childDeviceIds -NotePropertyValue $children -Force
    $payload = @{id=$gateway;name=$gateway;provider='tcp-server-gateway';state='disabled';channel='network';channelId=$network;protocol='modbus-rtu.v1';transport='TCP';configuration=$gatewayDetail.configuration}
    $null = Invoke-TestApi PATCH '/gateway/device' $payload
    Mark 'gateway-children-config'
    $null = Invoke-TestApi POST "/network/config/$network/_start"
    Mark 'network-start'
    $null = Invoke-TestApi POST "/gateway/device/$gateway/_startup"
    Mark 'gateway-start'
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync('192.168.5.19', $Port)
        if (-not $pending.Wait(5000) -or -not $client.Connected) { throw "TCP connection to test server:$Port failed" }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        Write-Host "TCP_CONNECTED address=192.168.5.19 port=$Port"
        Start-Sleep -Seconds 1
        foreach ($child in $children) {
            $stateResult = Invoke-TestApi GET "/device-instance/$child/state"
            $state = if ($stateResult -is [string]) { $stateResult } elseif ($stateResult.value) { $stateResult.value } else { [string]$stateResult }
            Write-Host "RUNTIME_STATE child=$child state=$state"
            if ($state -ne 'online') { throw "Connected gateway child $child runtime state is $state, expected online" }
        }
        while ($watch.Elapsed.TotalSeconds -lt 5) {
            if ($client.Client.Poll(200000, [Net.Sockets.SelectMode]::SelectRead) -and $client.Available -eq 0) { throw "TCP connection closed after $([math]::Round($watch.Elapsed.TotalSeconds,2)) seconds" }
        }
        if ($client.Client.Poll(1, [Net.Sockets.SelectMode]::SelectRead) -and $client.Available -eq 0) { throw 'TCP connection closed before final verification' }
        Write-Host "TCP_HELD_SECONDS=$([math]::Round($watch.Elapsed.TotalSeconds,2))"
        Mark 'tcp-connection-and-child-online'
    } finally { $client.Dispose() }
    Write-Host "READY_FOR_REDIS_READBACK manifest=$manifestPath"
} catch {
    Write-Host "TEST_FAILED manifest=$manifestPath error=$($_.Exception.Message)"
    throw
}
