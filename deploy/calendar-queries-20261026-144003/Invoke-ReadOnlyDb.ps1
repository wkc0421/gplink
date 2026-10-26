param([Parameter(Mandatory=$true)][string]$SqlFile)
$ErrorActionPreference = 'Stop'
$sql = Get-Content -LiteralPath $SqlFile -Raw -Encoding UTF8
if ($sql -match '\b(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|COPY|CALL|VACUUM|REINDEX|pg_stat_statements_reset)\b') {
    throw 'Only read-only SELECT inspection is allowed'
}
$credential = Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
$session = New-PSSession -ComputerName '192.168.5.19' -Credential $credential
try {
    Invoke-Command -Session $session -ScriptBlock {
        param($Sql)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $jar = [IO.Compression.ZipFile]::OpenRead('D:/GpLink2/application.jar')
        $texts = @{}
        try {
            foreach ($name in @('application.yml','application-default.yml','application-local.yml')) {
                $entry = $jar.GetEntry('BOOT-INF/classes/' + $name)
                if ($null -eq $entry) { continue }
                $reader = New-Object IO.StreamReader($entry.Open())
                try { $texts[$name] = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
        } finally { $jar.Dispose() }
        function Scalar([string]$Value) {
            $v = $Value.Trim()
            if ($v.StartsWith("'") -or $v.StartsWith('"')) { return $v.Substring(1,$v.Length-2) }
            return ($v -replace '\s+#.*$','').Trim()
        }
        $block = [regex]::Match($texts['application.yml'], '(?ms)^    r2dbc:[^\r\n]*\r?\n(?<body>.*?)(?=^    [\w-]+:|\z)').Groups['body'].Value
        $settings = @{}
        foreach ($m in [regex]::Matches($block, '(?m)^        (url|username|password):\s*(.+?)\s*$')) {
            $settings[$m.Groups[1].Value] = Scalar $m.Groups[2].Value
        }
        if ($settings.Count -ne 3) { throw 'Unable to load deployed database settings' }
        $extra = (Get-ItemProperty -LiteralPath HKLM:/SYSTEM/CurrentControlSet/Services/GpLink2/Parameters).AppEnvironmentExtra
        $variables = @{}
        foreach ($name in @('application-default.yml','application-local.yml')) {
            foreach ($m in [regex]::Matches([string]$texts[$name], '(?m)^(DB_[A-Z_]+):\s*(.+?)\s*$')) {
                $variables[$m.Groups[1].Value] = Scalar $m.Groups[2].Value
            }
        }
        Get-ChildItem Env: | ForEach-Object { $variables[$_.Name] = $_.Value }
        foreach ($line in $extra) {
            if ($line -match '^([^=]+)=(.*)$') { $variables[$matches[1]] = $matches[2] }
        }
        function Resolve-Setting([string]$Value) {
            [regex]::Replace($Value, '\$\{([^:}]+)(?::([^}]*))?\}', [System.Text.RegularExpressions.MatchEvaluator]{
                param($m)
                $key = $m.Groups[1].Value
                if ($variables.ContainsKey($key)) { return [string]$variables[$key] }
                return $m.Groups[2].Value
            })
        }
        $url = (Resolve-Setting $settings.url) -replace '^r2dbc:(pool:)?',''
        $uri = [Uri]$url
        if ($uri.Host -notin @('localhost','127.0.0.1','192.168.5.19')) { throw 'Unexpected database host' }
        $password = Resolve-Setting $settings.password
        $originalPassword = $env:PGPASSWORD
        $originalOptions = $env:PGOPTIONS
        $originalTimezone = $env:PGTZ
        $originalEncoding = $env:PGCLIENTENCODING
        try {
            $env:PGPASSWORD = $password
            $env:PGOPTIONS = '-c default_transaction_read_only=on -c statement_timeout=30000 -c lock_timeout=3000 -c application_name=codex_calendar_validation'
            $env:PGTZ = 'Asia/Shanghai'
            $env:PGCLIENTENCODING = 'UTF8'
            $port = if ($uri.Port -gt 0) { $uri.Port } else { 5432 }
            $OutputEncoding = New-Object Text.UTF8Encoding($false)
            ('BEGIN READ ONLY; ' + $Sql + ' ROLLBACK;') | & 'C:/Program Files/PostgreSQL/16/bin/psql.exe' -X -w -q -A -t -v ON_ERROR_STOP=1 `
                -h 127.0.0.1 -p $port -U (Resolve-Setting $settings.username) -d $uri.AbsolutePath.TrimStart('/')
            if ($LASTEXITCODE -ne 0) { throw 'Read-only database query failed' }
        } finally {
            $env:PGPASSWORD = $originalPassword
            $env:PGOPTIONS = $originalOptions
            $env:PGTZ = $originalTimezone
            $env:PGCLIENTENCODING = $originalEncoding
            $password = $null
        }
    } -ArgumentList $sql
} finally {
    Remove-PSSession $session
}
