param(
    [string]$BaseUrl = "http://127.0.0.1:8848",
    [string]$Token = "",
    [string]$LoginPath = "/authorize/login",
    [string]$Username = "admin",
    [string]$Password = "admin",
    [string]$LoginBodyJson = "",
    [string]$Property = "temperature",
    [string]$Event = "report",
    [int]$DeviceStart = 1,
    [int]$DeviceEnd = 5000,
    [int]$BatchSize = 500,
    [int]$SingleDeviceSamples = 3,
    [int]$Workers = 1,
    [int]$TimeoutSec = 60,
    [long]$StartTime = 0,
    [long]$EndTime = 0,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ($BatchSize -gt 500) {
    throw "BatchSize must be <= 500 because the server default max query size is 500."
}

if ($StartTime -le 0 -or $EndTime -le 0) {
    $now = [DateTimeOffset]::Now
    if ($EndTime -le 0) {
        $EndTime = $now.ToUnixTimeMilliseconds()
    }
    if ($StartTime -le 0) {
        $StartTime = $now.AddDays(-1).ToUnixTimeMilliseconds()
    }
}

$BaseUrl = $BaseUrl.TrimEnd("/")

function Get-PropertyValue {
    param($Object, [string]$Path)
    $current = $Object
    foreach ($part in $Path.Split(".")) {
        if ($null -eq $current) {
            return $null
        }
        $prop = $current.PSObject.Properties[$part]
        if ($null -eq $prop) {
            return $null
        }
        $current = $prop.Value
    }
    return $current
}

function Get-TokenFromLoginResult {
    param($Result)
    $paths = @(
        "token",
        "accessToken",
        "access_token",
        "result.token",
        "result.accessToken",
        "result.access_token",
        "result.value",
        "data.token",
        "data.accessToken",
        "data.access_token"
    )
    foreach ($path in $paths) {
        $value = Get-PropertyValue -Object $Result -Path $path
        if ($value -and $value -is [string]) {
            return $value
        }
    }
    return $null
}

function Request-AccessToken {
    if ($Token) {
        return $Token
    }
    if ($DryRun) {
        return ""
    }

    $loginUri = "$BaseUrl$LoginPath"
    if ($LoginBodyJson) {
        $bodyJson = $LoginBodyJson
    } else {
        $bodyJson = @{
            username = $Username
            password = $Password
        } | ConvertTo-Json -Depth 10 -Compress
    }

    try {
        $result = Invoke-RestMethod `
            -Method POST `
            -Uri $loginUri `
            -Body $bodyJson `
            -ContentType "application/json" `
            -TimeoutSec $TimeoutSec
        $accessToken = Get-TokenFromLoginResult -Result $result
        if (-not $accessToken) {
            throw "Login succeeded but token was not found in common fields: token/accessToken/access_token/result/data."
        }
        return $accessToken
    } catch {
        throw "Failed to get token from $loginUri. Pass -Token manually or adjust -LoginPath/-LoginBodyJson. $($_.Exception.Message)"
    }
}

$accessToken = Request-AccessToken
$headers = @{
    "Content-Type" = "application/json"
}
if ($accessToken) {
    $headers["X-Access-Token"] = $accessToken
    $headers["Authorization"] = "Bearer $accessToken"
}

function New-DeviceIds {
    param([int]$Start, [int]$End)
    $ids = New-Object System.Collections.Generic.List[string]
    for ($i = $Start; $i -le $End; $i++) {
        $ids.Add(("mqtt_simulator_{0:D6}" -f $i))
    }
    return $ids
}

function Split-Batches {
    param([string[]]$Items, [int]$Size)
    for ($i = 0; $i -lt $Items.Count; $i += $Size) {
        $end = [Math]::Min($i + $Size - 1, $Items.Count - 1)
        ,$Items[$i..$end]
    }
}

function New-QueryBody {
    param([long]$Start, [long]$End, [int]$PageSize = 20)
    return @{
        pageIndex = 0
        pageSize = $PageSize
        terms = @(
            @{
                column = "timestamp"
                termType = "btw"
                value = @($Start, $End)
            }
        )
        sorts = @(
            @{
                name = "timestamp"
                order = "desc"
            }
        )
    }
}

function New-RequestEntity {
    param([string[]]$Devices, [string]$Property, [long]$Start, [long]$End)
    return @{
        devices = @($Devices)
        properties = @($Property)
        aggregationRequest = @{
            query = @{
                interval = "1h"
                format = "yyyy-MM-dd HH:mm:ss"
                from = $Start
                to = $End
                limit = 24
                filter = @{
                    terms = @()
                }
            }
            columns = @(
                @{
                    property = $Property
                    alias = $Property
                    agg = "LAST"
                }
            )
        }
    }
}

function ConvertTo-Summary {
    param($Result)
    if ($null -eq $Result) {
        return "empty"
    }
    if ($Result -is [array]) {
        return "list[$($Result.Count)]"
    }
    if ($Result.PSObject.Properties.Name -contains "data" -and $Result.data -is [array]) {
        return "pager[data=$($Result.data.Count), total=$($Result.total)]"
    }
    return $Result.GetType().Name
}

function Invoke-TestCase {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [hashtable]$Query = $null
    )

    $uri = "$BaseUrl$Path"
    if ($Query) {
        $queryString = ($Query.GetEnumerator() | ForEach-Object {
            "$([Uri]::EscapeDataString($_.Key))=$([Uri]::EscapeDataString([string]$_.Value))"
        }) -join "&"
        $uri = "$uri`?$queryString"
    }

    $bodyJson = $null
    if ($null -ne $Body) {
        $bodyJson = $Body | ConvertTo-Json -Depth 20 -Compress
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        if ($DryRun) {
            $sw.Stop()
            return [pscustomobject]@{
                Name = $Name
                Ok = $true
                ElapsedMs = $sw.ElapsedMilliseconds
                Summary = "DRY_RUN $Method $uri"
            }
        }

        $invokeArgs = @{
            Method = $Method
            Uri = $uri
            Headers = $headers
            TimeoutSec = $TimeoutSec
        }
        if ($null -ne $bodyJson) {
            $invokeArgs["Body"] = $bodyJson
        }
        $result = Invoke-RestMethod @invokeArgs
        $sw.Stop()
        return [pscustomobject]@{
            Name = $Name
            Ok = $true
            ElapsedMs = $sw.ElapsedMilliseconds
            Summary = ConvertTo-Summary $result
        }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{
            Name = $Name
            Ok = $false
            ElapsedMs = $sw.ElapsedMilliseconds
            Summary = $_.Exception.Message
        }
    }
}

function Write-Result {
    param($Result)
    $status = if ($Result.Ok) { "OK" } else { "FAIL" }
    "{0,-6} {1,7}ms {2} -> {3}" -f "[$status]", $Result.ElapsedMs, $Result.Name, $Result.Summary
}

$devices = @(New-DeviceIds -Start $DeviceStart -End $DeviceEnd)
$sampleDevices = @($devices | Select-Object -First $SingleDeviceSamples)

Write-Host "Base URL: $BaseUrl"
Write-Host "Devices: $($devices[0])..$($devices[-1]) ($($devices.Count))"
Write-Host "Property: $Property, event: $Event"
Write-Host "Range: $StartTime..$EndTime"
Write-Host "Batch size: $BatchSize, workers: $Workers"

$results = New-Object System.Collections.Generic.List[object]

foreach ($deviceId in $sampleDevices) {
    $q = New-QueryBody -Start $StartTime -End $EndTime
    $singleCases = @(
        @{ Name = "GET latest properties pg $deviceId"; Method = "GET"; Path = "/api/v1/device-data/$deviceId/properties/pg" },
        @{ Name = "GET latest property pg $deviceId"; Method = "GET"; Path = "/api/v1/device-data/$deviceId/$Property/pg" },
        @{ Name = "GET latest properties redis $deviceId"; Method = "GET"; Path = "/api/v1/device-data/$deviceId/properties/redis" },
        @{ Name = "GET latest property redis $deviceId"; Method = "GET"; Path = "/api/v1/device-data/$deviceId/$Property/redis" },
        @{ Name = "POST history pg page $deviceId"; Method = "POST"; Path = "/api/v1/device-data/$deviceId/$Property/history/pg"; Body = $q },
        @{ Name = "POST history pg test $deviceId"; Method = "POST"; Path = "/api/v1/device-data/$deviceId/$Property/history/pg/test"; Body = $q },
        @{ Name = "POST history no-paging pg $deviceId"; Method = "POST"; Path = "/api/v1/device-data/$deviceId/$Property/history/no-paging/pg"; Body = @{ query = $q } },
        @{ Name = "POST history raw pg $deviceId"; Method = "POST"; Path = "/api/v1/device-data/$deviceId/$Property/history/no-paging/pg/raw"; Body = $q },
        @{ Name = "POST event history pg $deviceId"; Method = "POST"; Path = "/api/v1/device-data/$deviceId/event/$Event/history/pg"; Body = $q }
    )

    foreach ($case in $singleCases) {
        $result = Invoke-TestCase @case
        $results.Add($result)
        Write-Result $result
    }
}

$batchCases = New-Object System.Collections.Generic.List[hashtable]
$batchIndex = 0
foreach ($batch in Split-Batches -Items $devices -Size $BatchSize) {
    $batchIndex++
    $req = @{
        devices = @($batch)
        properties = @($Property)
    }
    $aggReq = New-RequestEntity -Devices $batch -Property $Property -Start $StartTime -End $EndTime
    $timeQuery = @{
        startTime = $StartTime
        endTime = $EndTime
    }
    $batchCases.Add(@{ Name = ("batch {0:D2} properties pg ({1})" -f $batchIndex, $batch.Count); Method = "POST"; Path = "/api/v1/device-data/devices/properties/pg"; Body = $req })
    $batchCases.Add(@{ Name = ("batch {0:D2} properties redis ({1})" -f $batchIndex, $batch.Count); Method = "POST"; Path = "/api/v1/device-data/devices/properties/redis"; Body = $req })
    $batchCases.Add(@{ Name = ("batch {0:D2} agg ({1})" -f $batchIndex, $batch.Count); Method = "POST"; Path = "/api/v1/device-data/devices/agg/_query"; Body = $aggReq })
    $batchCases.Add(@{ Name = ("batch {0:D2} interval ({1})" -f $batchIndex, $batch.Count); Method = "POST"; Path = "/api/v1/device-data/devices/interval"; Body = $req; Query = $timeQuery })
    $batchCases.Add(@{ Name = ("batch {0:D2} interval day history ({1})" -f $batchIndex, $batch.Count); Method = "POST"; Path = "/api/v1/device-data/devices/interval/day/history"; Body = $req; Query = $timeQuery })
}

foreach ($case in $batchCases) {
    $result = Invoke-TestCase @case
    $results.Add($result)
    Write-Result $result
}

$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ""
Write-Host "Total: $($results.Count), OK: $($results.Count - $failed.Count), FAIL: $($failed.Count)"

if ($failed.Count -gt 0) {
    exit 1
}
