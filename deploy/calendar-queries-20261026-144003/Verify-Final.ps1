$ErrorActionPreference='Stop'
$api='C:/Users/wkc/.codex/skills/test-gplink2-test-api/scripts/invoke-gplink2-test-api.ps1'
$updater='C:/Users/wkc/.codex/skills/update-gplink2-test-env/scripts/update-gplink2-test-env.ps1'
$ready=$false
for($attempt=0;$attempt -lt 12;$attempt++) {
    try { $config=Invoke-RestMethod -Uri http://192.168.5.19:8848/authorize/login/configs -TimeoutSec 5; $ready=$true; break } catch {}
    Start-Sleep -Seconds 3
}
if(!$ready) {throw 'Final backend HTTP readiness check failed'}
& $updater -Mode Check -LocalJar (Join-Path $PSScriptRoot 'application.jar') | Tee-Object -FilePath (Join-Path $PSScriptRoot 'backend-final-check.txt')
$from=[DateTimeOffset]::Parse('2026-09-14T00:00:00+08:00').ToUnixTimeMilliseconds()
$to=[DateTimeOffset]::Parse('2026-10-19T00:00:00+08:00').ToUnixTimeMilliseconds()
$checks=@(
    @{Name='aggregate-1w';Path='/api/v1/device-data/devices/agg/_query';Rows=6;Aggregate=$true},
    @{Name='aggregate-1q';Path='/api/v1/device-data/devices/agg/_query';Rows=2;Aggregate=$true},
    @{Name='interval-week';Path=('/api/v1/device-data/devices/interval/week/history?startTime='+$from+'&endTime='+$to);Rows=5;Aggregate=$false},
    @{Name='interval-quarter';Path=('/api/v1/device-data/devices/interval/quarter/history?startTime='+$from+'&endTime='+$to);Rows=2;Aggregate=$false}
)
$results=New-Object 'System.Collections.Generic.List[object]'
foreach($check in $checks) {
    $lines=@(& $api -Method POST -Path $check.Path -BodyFile (Join-Path $PSScriptRoot ($check.Name+'-request.json')) -AllowMutation -TimeoutSeconds 60 |
        Tee-Object -FilePath (Join-Path $PSScriptRoot ('final-'+$check.Name+'-http.txt')))
    $index=[Array]::IndexOf($lines,'RESPONSE:')
    if($index -lt 0 -or !($lines -contains 'HTTP_STATUS: 200')) {throw ('Final request failed: '+$check.Name)}
    $response=(($lines|Select-Object -Skip ($index+1)) -join "`n") | ConvertFrom-Json
    if($response.status -ne 200) {throw 'Final application response failed'}
    $rows=if($check.Aggregate){$response.result[0].PSObject.Properties['codex_counter_20260924_d005'].Value}else{$response.result[0]}
    if($rows.Count -ne $check.Rows) {throw ('Final row count differs: '+$check.Name)}
    if($check.Aggregate) {
        if(($rows|Measure-Object value -Sum).Sum -ne 26) {throw 'Final aggregate value differs from independent SQL'}
    } else {
        foreach($row in $rows){if([decimal]$row.startValue -ne 8 -or [decimal]$row.endValue -ne 8){throw 'Final interval boundary reading differs'}}
    }
    $result=[pscustomobject]@{Case=$check.Name;HttpStatus=200;Rows=$rows.Count;Status='PASS';Runtime='Original launch parameters restored'}
    $results.Add($result)
    $result | ConvertTo-Json -Compress
}
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'final-api-verification.json') -Encoding UTF8
$credential=Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
$session=New-PSSession -ComputerName 192.168.5.19 -Credential $credential
try {
    Invoke-Command -Session $session -ScriptBlock {
        $parameters=[string](Get-ItemProperty HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters).AppParameters
        if($parameters -ne '-jar D:\GpLink2\application.jar') {throw 'Diagnostic launch settings were not restored'}
        $listeners=@(Get-NetTCPConnection -LocalPort 18849 -State Listen -ErrorAction SilentlyContinue)
        if($listeners.Count -ne 0) {throw 'Temporary diagnostic listener is still active'}
        [pscustomobject]@{Service=(Get-Service GpLink2).Status.ToString();Parameters=$parameters;DiagnosticPortClosed=$true;
            NginxProcesses=@(Get-Process nginx -ErrorAction SilentlyContinue).Count;
            BackendSha256=(Get-FileHash D:/GpLink2/application.jar -Algorithm SHA256).Hash;
            FrontendIndexSha256=(Get-FileHash D:/tool/nginx-1.26.3/html/gpLink2/index.html -Algorithm SHA256).Hash} | ConvertTo-Json -Compress
    } | Tee-Object -FilePath (Join-Path $PSScriptRoot 'final-runtime-state.json')
} finally {Remove-PSSession $session}
& $api -Method GET -Path /actuator | Out-File -Encoding UTF8 (Join-Path $PSScriptRoot 'final-actuator.txt')
