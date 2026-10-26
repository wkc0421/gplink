$ErrorActionPreference='Stop'
$api='C:/Users/wkc/.codex/skills/test-gplink2-test-api/scripts/invoke-gplink2-test-api.ps1'
$diagnostics=Join-Path $PSScriptRoot 'Diagnostics.ps1'
$device='codex_counter_20260924_d005'
$property='counter_kwh'
$summaries=New-Object 'System.Collections.Generic.List[object]'
function Epoch([string]$LocalTime) { [DateTimeOffset]::Parse($LocalTime+'+08:00').ToUnixTimeMilliseconds() }
function Assert([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Metrics { (& $diagnostics -Mode Snapshot) | ConvertFrom-Json }
function Before-Window {
    $snapshot=Metrics
    $remainder=([DateTimeOffset]::Parse($snapshot.At).ToUnixTimeMilliseconds() % 5000)
    if ($remainder -gt 2000) {
        Start-Sleep -Milliseconds ([int](5500-$remainder))
        $snapshot=Metrics
    }
    return $snapshot
}
function Completed-Window {
    $snapshot=Metrics
    $remainder=([DateTimeOffset]::Parse($snapshot.At).ToUnixTimeMilliseconds() % 5000)
    Start-Sleep -Milliseconds ([int](5500-$remainder))
    return (Metrics)
}
function Count($Snapshot,[string]$Source) {
    $p=$Snapshot.Counts.PSObject.Properties[$Source]
    if ($null -eq $p) { return 0.0 }
    return [double]$p.Value
}
function Delta($Before,$After) {
    $result=@{}
    foreach ($name in @('day_snapshot','hour_snapshot','cagg_daily','cagg_hourly','raw','raw_absent','future')) {
        $result[$name]=(Count $After $name)-(Count $Before $name)
    }
    $result
}
function Query([string]$Name,[string]$Path,$Body) {
    $bodyFile=Join-Path $PSScriptRoot ($Name+'-request.json')
    $Body | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $bodyFile -Encoding UTF8
    $lines=@(& $api -Method POST -Path $Path -BodyFile $bodyFile -AllowMutation -TimeoutSeconds 60 |
        Tee-Object -FilePath (Join-Path $PSScriptRoot ($Name+'-http.txt')))
    $index=[Array]::IndexOf($lines,'RESPONSE:')
    Assert ($index -ge 0) ($Name+': response envelope missing')
    Assert (@($lines | Where-Object { $_ -eq 'HTTP_STATUS: 200' }).Count -eq 1) ($Name+': HTTP is not 200')
    $text=($lines | Select-Object -Skip ($index+1)) -join "`n"
    $response=$text | ConvertFrom-Json
    Assert ($response.status -eq 200) ($Name+': application status is not 200')
    $text | Set-Content -LiteralPath (Join-Path $PSScriptRoot ($Name+'-response.json')) -Encoding UTF8
    return $response
}
function Record([string]$Name,$Response,$Before,$After,$Details) {
    $entry=[ordered]@{Case=$Name;HttpStatus=200;Status='PASS';Details=$Details}
    if ($null -ne $Before) {
        $entry.MetricsBefore=$Before
        $entry.MetricsAfter=$After
        # The application's TimeSeriesMeterRegistry uses step counters, not cumulative counters.
        $entry.BoundarySourceWindowCounts=$After.Counts
        $entry.MetricStepSeconds=5
    }
    $summaries.Add([pscustomobject]$entry)
    Write-Output ($Name+': PASS '+($Details|ConvertTo-Json -Compress -Depth 4))
}
try {
    $from=Epoch '2026-09-14T00:00:00'
    $to=Epoch '2026-10-19T00:00:00'
    foreach ($interval in @('1w','1q')) {
        $body=@{devices=@($device);properties=@($property);aggregationRequest=@{
            query=@{from=$from;to=$to;interval=$interval;format='yyyy-MM-dd HH:mm:ss';limit=100};
            columns=@(@{property=$property;alias=('calendar_'+$interval+'_20261026');agg='SUM'})}}
        $response=Query ('aggregate-'+$interval) '/api/v1/device-data/devices/agg/_query' $body
        $rows=$response.result[0].PSObject.Properties[$device].Value
        $expectedTimes=if ($interval -eq '1w') {
            @('2026-10-19 00:00:00','2026-10-12 00:00:00','2026-10-05 00:00:00','2026-09-28 00:00:00','2026-09-21 00:00:00','2026-09-14 00:00:00')
        } else { @('2026-10-01 00:00:00','2026-07-01 00:00:00') }
        Assert ($rows.Count -eq $expectedTimes.Count) ($interval+': wrong bucket count')
        for ($i=0;$i -lt $rows.Count;$i++) {
            Assert ($rows[$i].time -eq $expectedTimes[$i]) ($interval+': wrong natural bucket boundary')
            $expected=if ($i -eq $rows.Count-1) {26} else {0}
            Assert ([decimal]$rows[$i].value -eq $expected) ($interval+': SUM differs from independent raw SQL')
        }
        Record ('aggregate-'+$interval) $response $null $null @{Buckets=$rows.Count;Sum=26;ComparedWith='expected.sql raw SQL';Interval=$interval}
    }
    $baseBody=@{devices=@($device);properties=@($property)}
    foreach ($type in @('week','quarter')) {
        $before=Before-Window
        $response=Query ('interval-'+$type) ('/api/v1/device-data/devices/interval/'+$type+'/history?startTime='+$from+'&endTime='+$to) $baseBody
        $after=Completed-Window
        $rows=$response.result[0]
        $bounds=if ($type -eq 'week') {
            @('2026-09-14T00:00:00','2026-09-21T00:00:00','2026-09-28T00:00:00','2026-10-05T00:00:00','2026-10-12T00:00:00','2026-10-19T00:00:00')
        } else { @('2026-09-14T00:00:00','2026-10-01T00:00:00','2026-10-19T00:00:00') }
        Assert ($rows.Count -eq $bounds.Count-1) ($type+': wrong segment count')
        for ($i=0;$i -lt $rows.Count;$i++) {
            Assert ($rows[$i].startTime -eq (Epoch $bounds[$i])) ($type+': wrong start boundary')
            Assert ($rows[$i].endTime -eq (Epoch $bounds[$i+1])) ($type+': wrong end boundary')
            Assert ([decimal]$rows[$i].startValue -eq 8 -and [decimal]$rows[$i].endValue -eq 8) ($type+': wrong carried boundary reading')
        }
        $delta=@{day_snapshot=(Count $after 'day_snapshot');hour_snapshot=(Count $after 'hour_snapshot')}
        $expectedDay=if ($type -eq 'week') {5} else {3}
        Assert ($delta.day_snapshot -eq $expectedDay) ($type+': expected eligible daily snapshot hits not observed')
        Record ('interval-'+$type) $response $before $after @{Segments=$rows.Count;ExpectedDailyHits=$expectedDay;Reading=8;KnownEmptyBaseline=$true}
    }
    # Identical authorized request should use the configured/versioned history cache.
    $before=Before-Window
    $cached=Query 'interval-quarter-cache' ('/api/v1/device-data/devices/interval/quarter/history?startTime='+$from+'&endTime='+$to) $baseBody
    $after=Completed-Window
    $delta=@{day_snapshot=(Count $after 'day_snapshot');hour_snapshot=(Count $after 'hour_snapshot')}
    Assert ($delta.day_snapshot -eq 0 -and $delta.hour_snapshot -eq 0) 'Repeated quarter request unexpectedly read snapshots again'
    Assert ($cached.result[0].Count -eq 2) 'Cached quarter response changed'
    Record 'interval-quarter-cache' $cached $before $after @{Segments=2;SnapshotReads=0}

    # All eligible natural boundaries: the daily table should resolve all four points.
    $fullFrom=Epoch '2026-09-28T00:00:00'
    $before=Before-Window
    $full=Query 'interval-week-all-snapshots' ('/api/v1/device-data/devices/interval/week/history?startTime='+$fullFrom+'&endTime='+$to) $baseBody
    $after=Completed-Window
    $delta=@{day_snapshot=(Count $after 'day_snapshot');hour_snapshot=(Count $after 'hour_snapshot')}
    Assert ($full.result[0].Count -eq 3) 'All-snapshot week range has wrong segment count'
    Assert ($delta.day_snapshot -eq 4 -and $delta.hour_snapshot -eq 0) 'All-snapshot week boundary source differs'
    Record 'interval-week-all-snapshots' $full $before $after @{Segments=3;DailySnapshotHits=4}

    foreach ($type in @('week','quarter')) {
        $partialFrom=Epoch '2026-09-20T12:30:00'
        $partialTo=Epoch '2026-10-21T18:45:00'
        $response=Query ('partial-'+$type) ('/api/v1/device-data/devices/interval/'+$type+'/history?startTime='+$partialFrom+'&endTime='+$partialTo) $baseBody
        $rows=$response.result[0]
        Assert ($rows[0].startTime -eq $partialFrom) ($type+': partial start was expanded')
        Assert ($rows[$rows.Count-1].endTime -eq $partialTo) ($type+': partial end was expanded')
        foreach ($row in $rows) { Assert ([decimal]$row.startValue -eq 8 -and [decimal]$row.endValue -eq 8) ($type+': partial reading changed') }
        Record ('partial-'+$type) $response $null $null @{Segments=$rows.Count;Start=$partialFrom;End=$partialTo;Reading=8}
    }
} finally {
    $summaries | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'api-validation-summary.json') -Encoding UTF8
}
