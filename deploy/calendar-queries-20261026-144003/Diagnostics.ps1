param([Parameter(Mandatory=$true)][ValidateSet('Enable','ActivateCounters','Read','Snapshot','Restore')][string]$Mode,
      [string]$Path='/actuator/metrics/gplink.counter.boundary.resolved')
$ErrorActionPreference='Stop'
$savedFile=Join-Path $PSScriptRoot 'original-service-parameters.json'
$credential=Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
$session=New-PSSession -ComputerName 192.168.5.19 -Credential $credential
try {
    if ($Mode -eq 'Enable') {
        if (Test-Path -LiteralPath $savedFile) { throw 'Original parameters already recorded; refusing overwrite' }
        $original=Invoke-Command -Session $session -ScriptBlock {
            if ((Get-Service GpLink2).Status -ne 'Running') { throw 'GpLink2 must be Running before deployment' }
            if (Get-NetTCPConnection -LocalPort 18849 -State Listen -ErrorAction SilentlyContinue) { throw 'Diagnostic port is already in use' }
            [string](Get-ItemProperty HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters).AppParameters
        }
        @{AppParameters=[string]$original; Port=18849; Address='127.0.0.1'} | ConvertTo-Json | Set-Content -LiteralPath $savedFile -Encoding UTF8
        Invoke-Command -Session $session -ScriptBlock {
            param($Original)
            if ($Original -ne '-jar D:\GpLink2\application.jar') { throw 'Unexpected original service parameters' }
            Set-ItemProperty HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters -Name AppParameters `
                -Value ($Original + ' --management.server.port=18849 --management.server.address=127.0.0.1 --management.endpoints.web.exposure.include=health,metrics')
            'Loopback-only diagnostics prepared for deployment restart'
        } -ArgumentList ([string]$original)
    } elseif ($Mode -eq 'ActivateCounters') {
        Invoke-Command -Session $session -ScriptBlock {
            $key='HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters'
            $current=[string](Get-ItemProperty $key).AppParameters
            if (!$current.Contains('--management.server.address=127.0.0.1') -or !$current.Contains('--management.server.port=18849')) { throw 'Expected loopback diagnostics are not configured' }
            if (!$current.Contains('--management.simple.metrics.export.enabled=true')) {
                $current+=' --management.simple.metrics.export.enabled=true'
            }
            if (!$current.Contains('--micrometer.time-series.metrics.default.step=5s')) { $current+=' --micrometer.time-series.metrics.default.step=5s' }
            Set-ItemProperty $key -Name AppParameters -Value $current
            Stop-Service GpLink2 -Force
            (Get-Service GpLink2).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))
            Start-Service GpLink2
            (Get-Service GpLink2).WaitForStatus('Running',[TimeSpan]::FromSeconds(60))
            'Temporary in-memory metrics registry enabled for loopback verification'
        }
    } elseif ($Mode -eq 'Read') {
        Invoke-Command -Session $session -ScriptBlock {
            param($Path)
            if ($Path -notmatch '^/actuator/(metrics|health)') { throw 'Only diagnostic metrics/health paths are permitted' }
            $response=Invoke-WebRequest -Uri ('http://127.0.0.1:18849'+$Path) -UseBasicParsing -TimeoutSec 15
            if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
        } -ArgumentList $Path
    } elseif ($Mode -eq 'Snapshot') {
        Invoke-Command -Session $session -ScriptBlock {
            function Read-Json([string]$Path) {
                $response=Invoke-WebRequest -Uri ('http://127.0.0.1:18849'+$Path) -UseBasicParsing -TimeoutSec 15
                $text=if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
                $text | ConvertFrom-Json
            }
            $name='gplink.counter.boundary.resolved'
            $names=(Read-Json '/actuator/metrics').names
            $counts=@{}
            $exists=$names -contains $name
            if ($exists) {
                $metric=Read-Json ('/actuator/metrics/'+$name)
                $sources=@($metric.availableTags | Where-Object tag -eq 'source' | ForEach-Object values)
                foreach ($source in $sources) {
                    $point=Read-Json ('/actuator/metrics/'+$name+'?tag='+[Uri]::EscapeDataString('source:'+$source))
                    $counts[$source]=[double]($point.measurements | Where-Object statistic -eq 'COUNT').value
                }
            }
            @{At=[DateTimeOffset]::Now.ToString('o'); Exists=$exists; Counts=$counts} | ConvertTo-Json -Depth 4 -Compress
        }
    } else {
        $original=(Get-Content -LiteralPath $savedFile -Raw -Encoding UTF8 | ConvertFrom-Json).AppParameters
        Invoke-Command -Session $session -ScriptBlock {
            param($Original)
            if ($Original -ne '-jar D:\GpLink2\application.jar') { throw 'Unexpected saved original parameters' }
            Set-ItemProperty HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters -Name AppParameters -Value $Original
            Stop-Service GpLink2 -Force
            (Get-Service GpLink2).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))
            Start-Service GpLink2
            (Get-Service GpLink2).WaitForStatus('Running',[TimeSpan]::FromSeconds(60))
            [pscustomobject]@{RestoredParameters=(Get-ItemProperty HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters).AppParameters;
                Service=(Get-Service GpLink2).Status.ToString(); Sha256=(Get-FileHash D:/GpLink2/application.jar -Algorithm SHA256).Hash} | ConvertTo-Json -Compress
        } -ArgumentList ([string]$original)
    }
} finally { Remove-PSSession $session }
