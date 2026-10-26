Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:DeployLogPath = $null
$script:DeploySecrets = @()

function Get-BundleRoot {
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

function Resolve-FullPath {
    param([Parameter(Mandatory = $true)][string]$BasePath, [Parameter(Mandatory = $true)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function Test-PathInsideRoot {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Candidate)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    $candidateFull = [System.IO.Path]::GetFullPath($Candidate)
    return $candidateFull.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-PathsOverlap {
    param([Parameter(Mandatory = $true)][string]$First, [Parameter(Mandatory = $true)][string]$Second)
    $firstFull = [System.IO.Path]::GetFullPath($First).TrimEnd('\')
    $secondFull = [System.IO.Path]::GetFullPath($Second).TrimEnd('\')
    if ([string]::Equals($firstFull, $secondFull, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    return $firstFull.StartsWith($secondFull + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
        $secondFull.StartsWith($firstFull + '\', [System.StringComparison]::OrdinalIgnoreCase)
}

function Read-DeployConfig {
    param([Parameter(Mandatory = $true)][string]$ConfigPath)
    $fullPath = [System.IO.Path]::GetFullPath($ConfigPath)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Configuration file not found: $fullPath"
    }
    $config = Import-PowerShellDataFile -LiteralPath $fullPath
    if (-not ($config -is [hashtable])) {
        throw 'Configuration must evaluate to a PowerShell data hashtable.'
    }
    return $config
}

function Test-IsIntegerValue {
    param($Value)
    if ($null -eq $Value) { return $false }
    return [Type]::GetTypeCode($Value.GetType()) -in @(
        [TypeCode]::Byte, [TypeCode]::SByte, [TypeCode]::Int16, [TypeCode]::UInt16,
        [TypeCode]::Int32, [TypeCode]::UInt32, [TypeCode]::Int64, [TypeCode]::UInt64
    )
}

function Test-IsNumericValue {
    param($Value)
    if ($null -eq $Value) { return $false }
    return [Type]::GetTypeCode($Value.GetType()) -in @(
        [TypeCode]::Byte, [TypeCode]::SByte, [TypeCode]::Int16, [TypeCode]::UInt16,
        [TypeCode]::Int32, [TypeCode]::UInt32, [TypeCode]::Int64, [TypeCode]::UInt64,
        [TypeCode]::Single, [TypeCode]::Double, [TypeCode]::Decimal
    )
}

function Get-DeployConfigValidationErrors {
    param([Parameter(Mandatory = $true)][hashtable]$Config)

    $errors = New-Object System.Collections.Generic.List[string]
    $requiredKeys = @(
        'PackageDirectory', 'PackageManifest', 'AppRoot', 'PgInstallRoot', 'PgData', 'OpenSslInstallRoot',
        'ServerPort', 'PgPort', 'ExternalHost', 'ExternalPort', 'PgServiceName', 'AppServiceName',
        'PostgresServiceAccount', 'PostgresSuperUser', 'PostgresSuperPassword', 'PostgresServicePassword',
        'DatabaseName', 'DatabaseUser', 'DatabasePassword', 'DatabaseSchema', 'RedisHost', 'RedisPort',
        'RedisUser', 'RedisPassword', 'RedisDatabase', 'AllowPasswordlessRedis', 'AdminUsername',
        'AdminUserPassword', 'JvmProfile', 'MinimumMemoryGB', 'MinimumAppFreeGB', 'MinimumPgFreeGB',
        'OpenApiFirewall', 'FirewallProfiles', 'RequireAuthenticode', 'RequireWindowsServer',
        'VcRuntimeArguments', 'OpenSslArguments', 'ServiceStartTimeoutSeconds',
        'RedisConnectTimeoutSeconds', 'ApiVerifyTimeoutSeconds'
    )
    $optionalEmptyStrings = @('RedisUser', 'RedisPassword')

    foreach ($key in $Config.Keys) {
        if ($requiredKeys -notcontains [string]$key) {
            $errors.Add("Unsupported configuration key '$key'. Check for a spelling mistake.")
        }
    }
    foreach ($key in $requiredKeys) {
        if (-not $Config.ContainsKey($key)) {
            $errors.Add("Missing configuration key '$key'.")
        }
    }

    $stringKeys = @(
        'PackageDirectory', 'PackageManifest', 'AppRoot', 'PgInstallRoot', 'PgData', 'OpenSslInstallRoot',
        'ExternalHost', 'PgServiceName', 'AppServiceName', 'PostgresServiceAccount', 'PostgresSuperUser',
        'PostgresSuperPassword', 'PostgresServicePassword', 'DatabaseName', 'DatabaseUser',
        'DatabasePassword', 'DatabaseSchema', 'RedisHost', 'RedisUser', 'RedisPassword',
        'AdminUsername', 'AdminUserPassword', 'JvmProfile'
    )
    foreach ($key in $stringKeys) {
        if (-not $Config.ContainsKey($key)) { continue }
        if (-not ($Config[$key] -is [string])) {
            $errors.Add("Configuration key '$key' must be a string.")
            continue
        }
        if ($optionalEmptyStrings -notcontains $key -and [string]::IsNullOrWhiteSpace([string]$Config[$key])) {
            $errors.Add("Configuration key '$key' cannot be empty.")
        }
    }

    $booleanKeys = @('AllowPasswordlessRedis', 'OpenApiFirewall', 'RequireAuthenticode', 'RequireWindowsServer')
    foreach ($key in $booleanKeys) {
        if ($Config.ContainsKey($key) -and -not ($Config[$key] -is [bool])) {
            $errors.Add("Configuration key '$key' must use `$true or `$false, not a quoted string.")
        }
    }
    foreach ($key in @('RequireAuthenticode', 'RequireWindowsServer')) {
        if ($Config.ContainsKey($key) -and $Config[$key] -is [bool] -and -not [bool]$Config[$key]) {
            $errors.Add("Configuration key '$key' is mandatory for this v1 bundle and must be `$true.")
        }
    }

    $integerRanges = @{
        ServerPort = @(1L, 65535L)
        PgPort = @(1L, 65535L)
        ExternalPort = @(1L, 65535L)
        RedisPort = @(1L, 65535L)
        RedisDatabase = @(0L, 2147483647L)
        ServiceStartTimeoutSeconds = @(1L, 86400L)
        RedisConnectTimeoutSeconds = @(1L, 86400L)
        ApiVerifyTimeoutSeconds = @(1L, 86400L)
    }
    foreach ($key in $integerRanges.Keys) {
        if (-not $Config.ContainsKey($key)) { continue }
        if (-not (Test-IsIntegerValue -Value $Config[$key])) {
            $errors.Add("Configuration key '$key' must be an integer.")
            continue
        }
        $value = [long]$Config[$key]
        $minimum = [long]$integerRanges[$key][0]
        $maximum = [long]$integerRanges[$key][1]
        if ($value -lt $minimum -or $value -gt $maximum) {
            $errors.Add("Configuration key '$key' must be between $minimum and $maximum.")
        }
    }
    if ($Config.ContainsKey('ServerPort') -and $Config.ContainsKey('PgPort') -and
        (Test-IsIntegerValue -Value $Config.ServerPort) -and (Test-IsIntegerValue -Value $Config.PgPort) -and
        [long]$Config.ServerPort -eq [long]$Config.PgPort) {
        $errors.Add("Configuration keys 'ServerPort' and 'PgPort' must use different ports.")
    }

    foreach ($key in @('MinimumMemoryGB', 'MinimumAppFreeGB', 'MinimumPgFreeGB')) {
        if (-not $Config.ContainsKey($key)) { continue }
        if (-not (Test-IsNumericValue -Value $Config[$key])) {
            $errors.Add("Configuration key '$key' must be numeric.")
            continue
        }
        $value = [double]$Config[$key]
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value) -or $value -le 0) {
            $errors.Add("Configuration key '$key' must be a finite number greater than zero.")
        }
    }
    if ($Config.ContainsKey('MinimumMemoryGB') -and (Test-IsNumericValue -Value $Config.MinimumMemoryGB) -and [double]$Config.MinimumMemoryGB -lt 16) {
        $errors.Add("Configuration key 'MinimumMemoryGB' cannot be lower than the supported minimum of 16 GB.")
    }

    foreach ($key in @('FirewallProfiles', 'VcRuntimeArguments', 'OpenSslArguments')) {
        if (-not $Config.ContainsKey($key)) { continue }
        $value = $Config[$key]
        if ($value -is [string] -or -not ($value -is [System.Collections.IEnumerable])) {
            $errors.Add("Configuration key '$key' must be an array of strings.")
            continue
        }
        $items = @($value)
        if ($items.Count -eq 0) {
            $errors.Add("Configuration key '$key' cannot be an empty array.")
            continue
        }
        foreach ($item in $items) {
            if (-not ($item -is [string]) -or [string]::IsNullOrWhiteSpace([string]$item)) {
                $errors.Add("Configuration key '$key' must contain only non-empty strings.")
                break
            }
            if ([string]$item -match '[\x00-\x1F]') {
                $errors.Add("Configuration key '$key' cannot contain control characters.")
                break
            }
        }
    }

    if ($Config.ContainsKey('JvmProfile') -and $Config.JvmProfile -is [string] -and [string]$Config.JvmProfile -notin @('Auto', 'Standard16GB', 'High32GB')) {
        $errors.Add("Configuration key 'JvmProfile' must be Auto, Standard16GB, or High32GB.")
    }
    if ($Config.ContainsKey('FirewallProfiles') -and -not ($Config.FirewallProfiles -is [string]) -and $Config.FirewallProfiles -is [System.Collections.IEnumerable]) {
        $profiles = @($Config.FirewallProfiles)
        foreach ($profile in $profiles) {
            if ($profile -is [string] -and [string]$profile -notin @('Domain', 'Private')) {
                $errors.Add("FirewallProfiles may contain only Domain and Private; '$profile' is not allowed.")
            }
        }
        if (@($profiles | Select-Object -Unique).Count -ne $profiles.Count) {
            $errors.Add('FirewallProfiles cannot contain duplicate entries.')
        }
    }

    $targetPathKeys = @('AppRoot', 'PgInstallRoot', 'PgData', 'OpenSslInstallRoot')
    $validTargetPaths = @{}
    foreach ($key in $targetPathKeys) {
        if (-not $Config.ContainsKey($key) -or -not ($Config[$key] -is [string]) -or [string]::IsNullOrWhiteSpace([string]$Config[$key])) { continue }
        try {
            $validTargetPaths[$key] = Assert-SafeAbsolutePath -Name $key -Path ([string]$Config[$key])
        } catch {
            $errors.Add($_.Exception.Message)
        }
    }
    for ($i = 0; $i -lt $targetPathKeys.Count; $i++) {
        for ($j = $i + 1; $j -lt $targetPathKeys.Count; $j++) {
            $firstKey = $targetPathKeys[$i]
            $secondKey = $targetPathKeys[$j]
            if ($validTargetPaths.ContainsKey($firstKey) -and $validTargetPaths.ContainsKey($secondKey) -and
                (Test-PathsOverlap -First $validTargetPaths[$firstKey] -Second $validTargetPaths[$secondKey])) {
                $errors.Add("Target paths '$firstKey' and '$secondKey' must be separate, non-overlapping directory trees.")
            }
        }
    }
    foreach ($key in @('PgInstallRoot', 'PgData')) {
        if ($validTargetPaths.ContainsKey($key) -and [string]$validTargetPaths[$key] -match '[^\x20-\x7E]') {
            $errors.Add("Configuration key '$key' must use an ASCII path supported by the PostgreSQL Windows installer.")
        }
    }

    foreach ($key in @('ExternalHost', 'RedisHost')) {
        if ($Config.ContainsKey($key) -and $Config[$key] -is [string] -and -not [string]::IsNullOrWhiteSpace([string]$Config[$key]) -and
            [Uri]::CheckHostName([string]$Config[$key]) -eq [System.UriHostNameType]::Unknown) {
            $errors.Add("Configuration key '$key' must be a host name or IP address without a URL scheme or path.")
        }
    }
    foreach ($key in @('PgServiceName', 'AppServiceName')) {
        if ($Config.ContainsKey($key) -and $Config[$key] -is [string] -and [string]$Config[$key] -notmatch '^[A-Za-z0-9_.-]+$') {
            $errors.Add("Configuration key '$key' contains unsupported service-name characters.")
        }
    }
    foreach ($key in @('PostgresSuperUser', 'DatabaseName', 'DatabaseUser', 'DatabaseSchema')) {
        if ($Config.ContainsKey($key) -and $Config[$key] -is [string] -and [string]$Config[$key] -notmatch '^[A-Za-z_][A-Za-z0-9_$-]{0,62}$') {
            $errors.Add("Configuration key '$key' must be a valid PostgreSQL identifier of at most 63 ASCII characters.")
        }
    }
    if ($Config.ContainsKey('PostgresServiceAccount') -and $Config.PostgresServiceAccount -is [string] -and [string]$Config.PostgresServiceAccount -notmatch '^[A-Za-z0-9_.\\-]+$') {
        $errors.Add("Configuration key 'PostgresServiceAccount' contains unsupported account-name characters.")
    }
    if ($Config.ContainsKey('PgServiceName') -and $Config.ContainsKey('AppServiceName') -and
        $Config.PgServiceName -is [string] -and $Config.AppServiceName -is [string] -and
        [string]::Equals([string]$Config.PgServiceName, [string]$Config.AppServiceName, [System.StringComparison]::OrdinalIgnoreCase)) {
        $errors.Add("Configuration keys 'PgServiceName' and 'AppServiceName' must be different.")
    }
    if ($Config.ContainsKey('PostgresSuperUser') -and $Config.ContainsKey('DatabaseUser') -and
        $Config.PostgresSuperUser -is [string] -and $Config.DatabaseUser -is [string] -and
        [string]::Equals([string]$Config.PostgresSuperUser, [string]$Config.DatabaseUser, [System.StringComparison]::OrdinalIgnoreCase)) {
        $errors.Add("Configuration key 'DatabaseUser' must be different from 'PostgresSuperUser'.")
    }
    foreach ($key in @('PostgresSuperPassword', 'PostgresServicePassword', 'DatabasePassword', 'RedisPassword', 'AdminUserPassword', 'RedisUser', 'AdminUsername')) {
        if ($Config.ContainsKey($key) -and $Config[$key] -is [string] -and [string]$Config[$key] -match '[\x00-\x1F]') {
            $errors.Add("Configuration key '$key' cannot contain control characters.")
        }
    }

    return @($errors.ToArray())
}

function Assert-DeployConfigValid {
    param([Parameter(Mandatory = $true)][hashtable]$Config)
    $validationErrors = @(Get-DeployConfigValidationErrors -Config $Config)
    if ($validationErrors.Count -gt 0) {
        throw ("Configuration validation failed:`n - " + ($validationErrors -join "`n - "))
    }
}

function Get-DeploymentContext {
    param([Parameter(Mandatory = $true)][string]$ConfigPath)
    $bundleRoot = Get-BundleRoot
    $config = Read-DeployConfig -ConfigPath $ConfigPath
    Assert-DeployConfigValid -Config $config
    foreach ($key in @('PackageDirectory', 'PackageManifest')) {
        if (-not $config.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$config[$key])) {
            throw "Missing configuration key: $key"
        }
    }
    $packageDirectory = Resolve-FullPath -BasePath $bundleRoot -Path ([string]$config.PackageDirectory)
    $manifestPath = Resolve-FullPath -BasePath $bundleRoot -Path ([string]$config.PackageManifest)
    if (-not (Test-PathInsideRoot -Root $bundleRoot -Candidate $packageDirectory)) {
        throw 'PackageDirectory must be located inside the offline bundle.'
    }
    if (-not (Test-PathInsideRoot -Root $bundleRoot -Candidate $manifestPath)) {
        throw 'PackageManifest must be located inside the offline bundle.'
    }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Package manifest not found: $manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($null -eq $manifest.packages -or [string]::IsNullOrWhiteSpace([string]$manifest.bundleId) -or [int]$manifest.schemaVersion -ne 1) {
        throw 'Package manifest is missing bundleId or packages.'
    }
    $bundleMetadataPath = Join-Path $bundleRoot 'manifest\bundle.json'
    if (-not (Test-Path -LiteralPath $bundleMetadataPath -PathType Leaf)) { throw 'Bundle metadata file is missing.' }
    $bundleMetadata = Get-Content -LiteralPath $bundleMetadataPath -Raw | ConvertFrom-Json
    if ([string]$bundleMetadata.bundleId -ne [string]$manifest.bundleId) { throw 'bundle.json and packages.json use different bundle IDs.' }
    if ([int]$bundleMetadata.schemaVersion -ne 1 -or $bundleMetadata.PSObject.Properties.Name -notcontains 'packageManifestSha256' -or
        [string]$bundleMetadata.packageManifestSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'bundle.json is missing a valid mandatory packageManifestSha256 value.'
    }
    $manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    if ([string]$bundleMetadata.packageManifestSha256 -ne $manifestHash) { throw 'packages.json hash does not match bundle.json.' }
    return [pscustomobject]@{
        BundleRoot = $bundleRoot
        ConfigPath = [System.IO.Path]::GetFullPath($ConfigPath)
        Config = $config
        PackageDirectory = $packageDirectory
        ManifestPath = $manifestPath
        Manifest = $manifest
        BundleMetadata = $bundleMetadata
        StatePath = Join-Path $env:ProgramData 'GpLink2\install-state.json'
    }
}

function Initialize-DeployLog {
    param([Parameter(Mandatory = $true)][string]$LogPath, [hashtable]$Config)
    $script:DeployLogPath = $LogPath
    $logDirectory = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $logDirectory)) {
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    }
    $secretKeys = @('PostgresSuperPassword', 'PostgresServicePassword', 'DatabasePassword', 'RedisPassword', 'AdminUserPassword')
    $script:DeploySecrets = @()
    if ($null -ne $Config) {
        foreach ($key in $secretKeys) {
            if ($Config.ContainsKey($key) -and -not [string]::IsNullOrEmpty([string]$Config[$key])) {
                $script:DeploySecrets += [string]$Config[$key]
            }
        }
    }
}

function Protect-LogText {
    param([AllowEmptyString()][string]$Text)
    $safe = [string]$Text
    foreach ($secret in $script:DeploySecrets) {
        if (-not [string]::IsNullOrEmpty($secret)) {
            $safe = $safe.Replace($secret, '***REDACTED***')
        }
    }
    $safe = [regex]::Replace($safe, '(?i)(authorization\s*[:=]\s*)([^\s,;]+)', '$1***REDACTED***')
    $safe = [regex]::Replace($safe, '(?i)(token\s*[:=]\s*)([^\s,;]+)', '$1***REDACTED***')
    return $safe
}

function Write-DeployLog {
    param([Parameter(Mandatory = $true)][string]$Message, [ValidateSet('INFO', 'WARN', 'ERROR')][string]$Level = 'INFO')
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, (Protect-LogText -Text $Message)
    Write-Host $line
    if (-not [string]::IsNullOrWhiteSpace($script:DeployLogPath)) {
        Add-Content -LiteralPath $script:DeployLogPath -Value $line -Encoding UTF8
    }
}

function New-CheckResult {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Category,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$Remediation = ''
    )
    return [pscustomobject]@{
        id = $Id
        category = $Category
        passed = $Passed
        message = Protect-LogText -Text $Message
        remediation = Protect-LogText -Text $Remediation
    }
}

function Write-JsonFile {
    param([Parameter(Mandatory = $true)]$InputObject, [Parameter(Mandatory = $true)][string]$Path, [int]$Depth = 8)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $json = ($InputObject | ConvertTo-Json -Depth $Depth) + [Environment]::NewLine
    $temporary = Join-Path $parent ('.' + [System.IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path $parent ('.' + [System.IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.bak')
    $encoding = New-Object System.Text.UTF8Encoding($false)
    try {
        [System.IO.File]::WriteAllText($temporary, $json, $encoding)
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            [System.IO.File]::Replace($temporary, $Path, $backup, $true)
            Remove-Item -LiteralPath $backup -Force
        } else {
            [System.IO.File]::Move($temporary, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    }
}

function Write-Utf8NoBomFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $temporary = Join-Path $parent ('.' + [System.IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path $parent ('.' + [System.IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.bak')
    $encoding = New-Object System.Text.UTF8Encoding($false)
    try {
        [System.IO.File]::WriteAllText($temporary, $Content, $encoding)
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            [System.IO.File]::Replace($temporary, $Path, $backup, $true)
            Remove-Item -LiteralPath $backup -Force
        } else {
            [System.IO.File]::Move($temporary, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    }
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-PlaceholderSecret {
    param([AllowEmptyString()][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $true }
    return ($Value -match '^<.*>$' -or $Value -match '(?i)change[_-]?me|placeholder|example|password_here')
}

function Assert-SafeAbsolutePath {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Name)
    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        throw "$Name must be an absolute path."
    }
    if ($Path.IndexOfAny([System.IO.Path]::GetInvalidPathChars()) -ge 0) {
        throw "$Name contains invalid path characters."
    }
    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($full)
    if ($root -notmatch '^[A-Za-z]:\\$') {
        throw "$Name must be a local drive-letter path; UNC and device paths are not supported."
    }
    if ($full -eq $root) {
        throw "$Name cannot be a drive root."
    }
    $relative = $full.Substring($root.Length)
    if ($relative -match '[:*?]') { throw "$Name contains unsupported wildcard or alternate-stream characters." }
    foreach ($segment in $relative.Split('\')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment.EndsWith('.') -or $segment.EndsWith(' ')) {
            throw "$Name contains an empty segment or a segment ending in a dot or space."
        }
        if ($segment -match '(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$') {
            throw "$Name contains a reserved Windows device name."
        }
    }
    return $full
}

function Get-WindowsServiceExecutablePath {
    param([Parameter(Mandatory = $true)][string]$PathName)
    $expanded = [Environment]::ExpandEnvironmentVariables($PathName).Trim()
    if ($expanded.StartsWith('"')) {
        $closingQuote = $expanded.IndexOf('"', 1)
        if ($closingQuote -lt 2) { throw "Invalid quoted Windows service PathName: $PathName" }
        return [System.IO.Path]::GetFullPath($expanded.Substring(1, $closingQuote - 1))
    }
    $match = [regex]::Match($expanded, '^(?<path>.+?\.exe)(?:\s|$)', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { throw "Could not parse Windows service executable path: $PathName" }
    return [System.IO.Path]::GetFullPath($match.Groups['path'].Value)
}

function Get-WindowsCommandLineArgumentValue {
    param(
        [Parameter(Mandatory = $true)][string]$CommandLine,
        [Parameter(Mandatory = $true)][ValidatePattern('^-[A-Za-z0-9]+$')][string]$ArgumentName
    )
    $pattern = '(?i)(?:^|\s)' + [regex]::Escape($ArgumentName) + '(?:\s+|=)(?:"(?<quoted>[^"]+)"|(?<plain>\S+))'
    $match = [regex]::Match($CommandLine, $pattern)
    if (-not $match.Success) { return $null }
    if ($match.Groups['quoted'].Success) { return $match.Groups['quoted'].Value }
    return $match.Groups['plain'].Value
}

function ConvertTo-NormalizedWindowsAccount {
    param([Parameter(Mandatory = $true)][string]$Account, [string]$ComputerName = $env:COMPUTERNAME)
    $trimmed = $Account.Trim()
    if ($trimmed.StartsWith('.\')) { return ($ComputerName + '\' + $trimmed.Substring(2)).ToUpperInvariant() }
    if (-not $trimmed.Contains('\')) { return ($ComputerName + '\' + $trimmed).ToUpperInvariant() }
    $parts = $trimmed.Split('\', 2)
    if ([string]::Equals($parts[0], $ComputerName, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($ComputerName + '\' + $parts[1]).ToUpperInvariant()
    }
    return $trimmed.ToUpperInvariant()
}

function Test-WindowsAccountMatches {
    param(
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Actual,
        [string]$ComputerName = $env:COMPUTERNAME
    )
    return (ConvertTo-NormalizedWindowsAccount -Account $Expected -ComputerName $ComputerName) -eq
        (ConvertTo-NormalizedWindowsAccount -Account $Actual -ComputerName $ComputerName)
}

function Get-GpLinkFirewallRuleName {
    param([Parameter(Mandatory = $true)][int]$Port)
    return "GpLink2-API-$Port"
}

function Get-GpLinkFirewallRuleDescription {
    param([Parameter(Mandatory = $true)][string]$BundleId)
    return "Managed by GPLink2 offline bundle $BundleId"
}

function Test-GpLinkFirewallRuleOwned {
    param(
        [Parameter(Mandatory = $true)]$Rule,
        [Parameter(Mandatory = $true)][string]$BundleId,
        [string]$StatePath
    )
    $description = Get-GpLinkFirewallRuleDescription -BundleId $BundleId
    if ([string]$Rule.Group -eq 'GPLink2 Offline Deployment' -and [string]$Rule.Description -eq $description) { return $true }
    return $false
}

function Test-WindowsCredential {
    param(
        [Parameter(Mandatory = $true)][string]$UserName,
        [Parameter(Mandatory = $true)][string]$Domain,
        [Parameter(Mandatory = $true)][string]$Password
    )
    if ($null -eq ('GpLink.NativeLogon' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace GpLink {
    public static class NativeLogon {
        [DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
        public static extern bool LogonUser(string userName, string domain, string password, int logonType, int logonProvider, out IntPtr token);
        [DllImport("kernel32.dll", SetLastError=true)]
        public static extern bool CloseHandle(IntPtr handle);
    }
}
'@
    }
    $token = [IntPtr]::Zero
    try {
        return [GpLink.NativeLogon]::LogonUser($UserName, $Domain, $Password, 3, 0, [ref]$token)
    } finally {
        if ($token -ne [IntPtr]::Zero) { [void][GpLink.NativeLogon]::CloseHandle($token) }
    }
}

function Get-DriveInfoForPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $root = [System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($Path))
    if ([string]::IsNullOrWhiteSpace($root)) { throw "Cannot determine drive for $Path" }
    return New-Object System.IO.DriveInfo($root)
}

function Get-SupportedWindowsServerRelease {
    param(
        [Parameter(Mandatory = $true)][int]$ProductType,
        [Parameter(Mandatory = $true)][int]$BuildNumber
    )
    if ($ProductType -eq 1) {
        switch ($BuildNumber) {
            22000 { return '11 21H2' }
            22621 { return '11 22H2' }
            22631 { return '11 23H2' }
            26100 { return '11 24H2' }
            26200 { return '11 25H2' }
            default { return $null }
        }
    }
    switch ($BuildNumber) {
        17763 { return '2019' }
        20348 { return '2022' }
        26100 { return '2025' }
        default { return $null }
    }
}

function Test-TcpPortInUse {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
            return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
        }
    } catch { }
    $lines = & netstat.exe -ano -p tcp 2>$null
    return $null -ne ($lines | Where-Object { $_ -match (':{0}\s+.*LISTENING' -f $Port) } | Select-Object -First 1)
}

function Get-TcpListeningProcessIds {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
            return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
        }
    } catch { }
    $processIds = @()
    foreach ($line in (& netstat.exe -ano -p tcp 2>$null)) {
        if ($line -match (':{0}\s+.*LISTENING\s+(\d+)\s*$' -f $Port)) { $processIds += [int]$matches[1] }
    }
    return @($processIds | Sort-Object -Unique)
}

function Test-ProcessDescendsFrom {
    param([Parameter(Mandatory = $true)][int]$ProcessId, [Parameter(Mandatory = $true)][int]$AncestorProcessId)
    $current = $ProcessId
    $visited = @{}
    while ($current -gt 0 -and -not $visited.ContainsKey($current)) {
        if ($current -eq $AncestorProcessId) { return $true }
        $visited[$current] = $true
        $process = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $current) -ErrorAction SilentlyContinue
        if ($null -eq $process) { break }
        $current = [int]$process.ParentProcessId
    }
    return $false
}

function Test-TcpPortOwnedByService {
    param([Parameter(Mandatory = $true)][int]$Port, [Parameter(Mandatory = $true)][string]$ServiceName)
    $service = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $ServiceName.Replace("'", "''")) -ErrorAction SilentlyContinue
    if ($null -eq $service -or [int]$service.ProcessId -le 0) { return $false }
    $listenerProcessIds = @(Get-TcpListeningProcessIds -Port $Port)
    if ($listenerProcessIds.Count -eq 0) { return $false }
    foreach ($listenerProcessId in $listenerProcessIds) {
        if (-not (Test-ProcessDescendsFrom -ProcessId ([int]$listenerProcessId) -AncestorProcessId ([int]$service.ProcessId))) { return $false }
    }
    return $true
}

function Get-ServiceOrNull {
    param([Parameter(Mandatory = $true)][string]$Name)
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Get-ManifestPackage {
    param([Parameter(Mandatory = $true)]$Manifest, [Parameter(Mandatory = $true)][string]$Id)
    $items = @($Manifest.packages | Where-Object { $_.id -eq $Id })
    if ($items.Count -ne 1) { throw "Manifest must contain exactly one package with id '$Id'." }
    return $items[0]
}

function Test-PackageFile {
    param(
        [Parameter(Mandatory = $true)]$Package,
        [Parameter(Mandatory = $true)][string]$PackageDirectory,
        [bool]$RequireAuthenticode = $true
    )
    if ([string]::IsNullOrWhiteSpace([string]$Package.file) -or ([string]$Package.file) -ne [System.IO.Path]::GetFileName([string]$Package.file)) {
        throw "Invalid package filename for $($Package.id)."
    }
    if ([string]$Package.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw "Manifest SHA-256 is not finalized for $($Package.id). Run Prepare-Bundle.ps1."
    }
    $path = Join-Path $PackageDirectory ([string]$Package.file)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Package file is missing: $($Package.file)" }
    $item = Get-Item -LiteralPath $path
    if ($item.Length -le 0) { throw "Package file is empty: $($Package.file)" }
    if ([long]$Package.size -gt 0 -and $item.Length -ne [long]$Package.size) {
        throw "Package size mismatch for $($Package.file)."
    }
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actualHash -ne ([string]$Package.sha256).ToUpperInvariant()) {
        throw "SHA-256 mismatch for $($Package.file)."
    }
    if ($RequireAuthenticode -and [bool]$Package.authenticode) {
        $signature = Get-AuthenticodeSignature -LiteralPath $path
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Authenticode signature is not valid for $($Package.file): $($signature.Status)"
        }
    }
    return [pscustomobject]@{ Path = $path; Hash = $actualHash; Size = $item.Length }
}

function Get-ZipEntryNames {
    param([Parameter(Mandatory = $true)][string]$Path)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try { return @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') }) }
    finally { $archive.Dispose() }
}

function Get-PeMachineType {
    param([Parameter(Mandatory = $true)][string]$Path)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $reader = New-Object System.IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "File is not a PE executable: $Path" }
        $stream.Position = 0x3C
        $peOffset = $reader.ReadInt32()
        if ($peOffset -lt 0 -or $peOffset -gt ($stream.Length - 6)) { throw 'Invalid PE header offset.' }
        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) { throw 'Invalid PE signature.' }
        return $reader.ReadUInt16()
    } finally { $reader.Dispose(); $stream.Dispose() }
}

function Test-JarContent {
    param([Parameter(Mandatory = $true)][string]$Path)
    $names = Get-ZipEntryNames -Path $Path
    foreach ($required in @('META-INF/MANIFEST.MF', 'BOOT-INF/classes/application.yml')) {
        if ($names -notcontains $required) { throw "application.jar is missing $required" }
    }
    if ($null -eq ($names | Where-Object { $_ -like 'BOOT-INF/lib/*.jar' } | Select-Object -First 1)) {
        throw 'application.jar does not contain Spring Boot dependencies.'
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
        $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
        try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    if ($manifestText -notmatch '(?m)^Main-Class:\s*\S+' -or $manifestText -notmatch '(?m)^Start-Class:\s*\S+') {
        throw 'application.jar manifest is missing Main-Class or Start-Class.'
    }
}

function Test-ModbusProtocolJar {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Modbus protocol JAR is missing: $Path" }
    $names = Get-ZipEntryNames -Path $Path
    foreach ($required in @(
        'META-INF/MANIFEST.MF',
        'META-INF/services/org.jetlinks.core.spi.ProtocolSupportProvider',
        'org/jetlinks/protocol/modbus/ModbusProtocolSupportProvider.class'
    )) {
        if ($names -notcontains $required) { throw "Modbus protocol JAR is missing $required." }
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $serviceEntry = $archive.GetEntry('META-INF/services/org.jetlinks.core.spi.ProtocolSupportProvider')
        $reader = New-Object System.IO.StreamReader($serviceEntry.Open())
        try { $serviceText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    $providers = @($serviceText -split "`r?`n" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($providers -notcontains 'org.jetlinks.protocol.modbus.ModbusProtocolSupportProvider') {
        throw 'Modbus protocol JAR service descriptor does not declare ModbusProtocolSupportProvider.'
    }
}

function Test-BaselineSql {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'GPLink2 baseline SQL is missing.' }
    $text = [System.IO.File]::ReadAllText($Path)
    if ([string]::IsNullOrWhiteSpace($text)) { throw 'GPLink2 baseline SQL is empty.' }
    foreach ($required in @(
        '\set ON_ERROR_STOP on',
        'BEGIN;',
        'COMMIT;',
        'gplink_deployment_baseline',
        "'2068932624811515904'",
        "'2068926208973430784'",
        "'gplink-baseline-mbrtu'",
        "'gplink-baseline-khmqtt'"
    )) {
        if (-not $text.Contains($required)) { throw "GPLink2 baseline SQL is missing required marker: $required" }
    }
    if ($text.Contains('-- GPLINK_BASELINE_SEED')) { throw 'GPLink2 baseline SQL still contains the unexpanded seed marker.' }
    if ($text -match '(?i)192\.168\.5\.19|https?://|access[_-]?key') {
        throw 'GPLink2 baseline SQL contains a forbidden test-server address, remote URL, or access-key field.'
    }
    if (@([regex]::Matches($text, '(?im)^INSERT INTO dev_protocol\s')).Count -ne 0) {
        throw 'GPLink2 baseline SQL must not seed protocol rows.'
    }
    if (@([regex]::Matches($text, '(?im)^INSERT INTO network_config\s')).Count -ne 2) {
        throw 'GPLink2 baseline SQL must seed exactly two network component rows.'
    }
    if (@([regex]::Matches($text, '(?im)^INSERT INTO s_file\s')).Count -ne 0) {
        throw 'GPLink2 baseline SQL must not register protocol JAR files.'
    }
    if ($text -notmatch '(?im)^DELETE FROM dev_protocol\s*;') {
        throw 'GPLink2 baseline SQL must clear all protocol rows.'
    }
}

function Test-TimescaleArchive {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Version, [Parameter(Mandatory = $true)][int]$PostgresMajor)
    $names = Get-ZipEntryNames -Path $Path
    $dll = $names | Where-Object { $_ -match '(?i)(^|/)timescaledb[^/]*\.dll$' } | Select-Object -First 1
    $control = $names | Where-Object { $_ -match '(?i)(^|/)timescaledb\.control$' } | Select-Object -First 1
    $sql = $names | Where-Object { $_ -match ('(?i)(^|/)timescaledb--{0}\.sql$' -f [regex]::Escape($Version)) } | Select-Object -First 1
    if ($null -eq $dll -or $null -eq $control -or $null -eq $sql) {
        throw 'TimescaleDB archive is missing a required DLL, control file, or versioned SQL file.'
    }
    if ([System.IO.Path]::GetFileName($Path) -notmatch ("postgresql-$PostgresMajor")) {
        throw "TimescaleDB package filename does not match PostgreSQL $PostgresMajor."
    }
}

function Get-JavaRuntimeInfo {
    param(
        [Parameter(Mandatory = $true)][string]$JavaPath,
        [string]$ExpectedVersion,
        [switch]$RequireJdk
    )
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = (& $JavaPath '-XshowSettings:properties' '-version' 2>&1 | Out-String)
        $javaExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($javaExitCode -ne 0) { throw "Java executable could not run: $JavaPath" }

    $versionMatch = [regex]::Match($output, '(?m)^\s*java\.version\s*=\s*(\S+)\s*$')
    $architectureMatch = [regex]::Match($output, '(?m)^\s*os\.arch\s*=\s*(\S+)\s*$')
    if (-not $versionMatch.Success -or $versionMatch.Groups[1].Value -notmatch '^17(?:\.|$)') { throw "Java executable is not Java 17: $JavaPath" }
    if (-not $architectureMatch.Success -or $architectureMatch.Groups[1].Value -notin @('amd64', 'x86_64')) { throw "Java executable is not x64: $JavaPath" }
    $version = $versionMatch.Groups[1].Value
    if (-not [string]::IsNullOrWhiteSpace($ExpectedVersion)) {
        $expectedRuntimeVersion = ($ExpectedVersion -split '\+')[0]
        if ($version -ne $expectedRuntimeVersion) { throw "Java runtime is version $version, expected $expectedRuntimeVersion." }
    }

    if ($RequireJdk) {
        $javaHome = Split-Path -Parent (Split-Path -Parent $JavaPath)
        $javacPath = Join-Path $javaHome 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $javacPath -PathType Leaf)) { throw "JDK compiler is missing: $javacPath" }
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $compilerOutput = (& $javacPath '-version' 2>&1 | Out-String).Trim()
            $compilerExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        if ($compilerExitCode -ne 0 -or $compilerOutput -notmatch '^javac\s+17(?:\.|$)') { throw "JDK compiler is not Java 17: $javacPath" }
    }

    return [pscustomobject]@{
        JavaPath = [System.IO.Path]::GetFullPath($JavaPath)
        JavaHome = [System.IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $JavaPath)))
        Version = $version
        Architecture = $architectureMatch.Groups[1].Value
        IsJdk = [bool]$RequireJdk
    }
}

function Get-PreferredSystemJdk {
    param([string]$JdkHome = 'D:\Program Files\Java\jdk-17')
    $javaPath = $JdkHome.TrimEnd('\') + '\bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
        return [pscustomobject]@{ Available = $false; JavaPath = $null; JavaHome = $JdkHome; Version = $null; Reason = "JDK not found at $JdkHome." }
    }
    try {
        $runtime = Get-JavaRuntimeInfo -JavaPath $javaPath -RequireJdk
        return [pscustomobject]@{
            Available = $true
            JavaPath = $runtime.JavaPath
            JavaHome = $runtime.JavaHome
            Version = $runtime.Version
            Reason = $null
        }
    } catch {
        return [pscustomobject]@{ Available = $false; JavaPath = $null; JavaHome = $JdkHome; Version = $null; Reason = $_.Exception.Message }
    }
}

function Test-JreArchive {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$ExpectedVersion)
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('gplink-jre-check-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($Path, $temp)
        $java = Get-ChildItem -LiteralPath $temp -Filter java.exe -Recurse | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
        if ($null -eq $java) { throw 'JRE archive does not contain bin\java.exe.' }
        [void](Get-JavaRuntimeInfo -JavaPath $java.FullName -ExpectedVersion $ExpectedVersion)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

function New-RedisCommandBytes {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('*').Append($Arguments.Count).Append("`r`n")
    foreach ($argument in $Arguments) {
        $length = $encoding.GetByteCount([string]$argument)
        [void]$builder.Append('$').Append($length).Append("`r`n").Append([string]$argument).Append("`r`n")
    }
    return $encoding.GetBytes($builder.ToString())
}

function Read-RedisLine {
    param([Parameter(Mandatory = $true)][System.IO.Stream]$Stream)
    $bytes = New-Object System.Collections.Generic.List[byte]
    while ($true) {
        $value = $Stream.ReadByte()
        if ($value -lt 0) { throw 'Redis closed the connection unexpectedly.' }
        if ($value -eq 13) {
            $next = $Stream.ReadByte()
            if ($next -ne 10) { throw 'Invalid Redis response terminator.' }
            break
        }
        $bytes.Add([byte]$value)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes.ToArray())
}

function Invoke-RedisSimpleCommand {
    param([Parameter(Mandatory = $true)][System.IO.Stream]$Stream, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $bytes = New-RedisCommandBytes -Arguments $Arguments
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
    $line = Read-RedisLine -Stream $Stream
    if ($line.StartsWith('-')) { throw ('Redis rejected {0}: {1}' -f $Arguments[0], $line.Substring(1)) }
    if (-not ($line.StartsWith('+') -or $line.StartsWith(':'))) { throw "Unexpected Redis response type for $($Arguments[0])." }
    return $line.Substring(1)
}

function Test-ExternalRedis {
    param([Parameter(Mandatory = $true)][hashtable]$Config)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.BeginConnect([string]$Config.RedisHost, [int]$Config.RedisPort, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne([TimeSpan]::FromSeconds([int]$Config.RedisConnectTimeoutSeconds))) {
            throw 'Redis TCP connection timed out.'
        }
        $client.EndConnect($connect)
        $client.ReceiveTimeout = [int]$Config.RedisConnectTimeoutSeconds * 1000
        $client.SendTimeout = [int]$Config.RedisConnectTimeoutSeconds * 1000
        $stream = $client.GetStream()
        if (-not [string]::IsNullOrEmpty([string]$Config.RedisPassword)) {
            if ([string]::IsNullOrEmpty([string]$Config.RedisUser)) {
                $auth = Invoke-RedisSimpleCommand -Stream $stream -Arguments @('AUTH', [string]$Config.RedisPassword)
            } else {
                $auth = Invoke-RedisSimpleCommand -Stream $stream -Arguments @('AUTH', [string]$Config.RedisUser, [string]$Config.RedisPassword)
            }
            if ($auth -ne 'OK') { throw 'Redis AUTH did not return OK.' }
        } elseif (-not [bool]$Config.AllowPasswordlessRedis) {
            throw 'RedisPassword is empty and AllowPasswordlessRedis is false.'
        }
        $select = Invoke-RedisSimpleCommand -Stream $stream -Arguments @('SELECT', [string]$Config.RedisDatabase)
        if ($select -ne 'OK') { throw 'Redis SELECT did not return OK.' }
        $pong = Invoke-RedisSimpleCommand -Stream $stream -Arguments @('PING')
        if ($pong -ne 'PONG') { throw 'Redis PING did not return PONG.' }
        return $true
    } finally {
        $client.Dispose()
    }
}

function ConvertTo-YamlSingleQuoted {
    param([AllowEmptyString()][string]$Value)
    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function Quote-PgIdentifier {
    param([Parameter(Mandatory = $true)][string]$Value)
    return '"' + $Value.Replace('"', '""') + '"'
}

function Quote-PgLiteral {
    param([AllowEmptyString()][string]$Value)
    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory = $true)][string]$Description,
        [int[]]$SuccessExitCodes = @(0),
        [switch]$CaptureOutput
    )
    Write-DeployLog -Message $Description
    if ($CaptureOutput) {
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = & $FilePath @ArgumentList 2>&1
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        if ($SuccessExitCodes -notcontains $exitCode) { throw "$Description failed with exit code $exitCode." }
        return $output
    }
    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -Wait -PassThru -WindowStyle Hidden
    if ($SuccessExitCodes -notcontains $process.ExitCode) { throw "$Description failed with exit code $($process.ExitCode)." }
    return $process.ExitCode
}

function Invoke-Psql {
    param(
        [Parameter(Mandatory = $true)][string]$PsqlPath,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$User,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$Sql,
        [string]$File,
        [hashtable]$Variables = @{}
    )
    $oldPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $Password
        $arguments = @('-X', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', [string]$Port, '-U', $User, '-d', $Database)
        foreach ($entry in @($Variables.GetEnumerator() | Sort-Object Key)) {
            if ([string]$entry.Key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid psql variable name: $($entry.Key)" }
            if ([string]$entry.Value -match '[\r\n\x00]') { throw "psql variable '$($entry.Key)' contains an invalid control character." }
            $arguments += @('-v', ([string]$entry.Key + '=' + [string]$entry.Value))
        }
        if (-not [string]::IsNullOrWhiteSpace($File)) { $arguments += @('-q', '-f', $File) }
        else { $arguments += @('-At', '-c', $Sql) }
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = & $PsqlPath @arguments 2>&1
            $psqlExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        if ($psqlExitCode -ne 0) { throw ('psql failed: ' + (($output | Out-String).Trim())) }
        return @($output)
    } finally {
        $env:PGPASSWORD = $oldPassword
    }
}

function Wait-ServiceRunning {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
        if ($null -ne $service -and $service.Status -eq 'Running') { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Service '$Name' did not reach Running within $TimeoutSeconds seconds."
}

function Wait-TcpPort {
    param([Parameter(Mandatory = $true)][string]$HostName, [Parameter(Mandatory = $true)][int]$Port, [Parameter(Mandatory = $true)][int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $task = $client.ConnectAsync($HostName, $Port)
            if ($task.Wait(1000) -and $client.Connected) { return }
        } catch { } finally { $client.Dispose() }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "TCP $HostName`:$Port did not become available within $TimeoutSeconds seconds."
}

function Get-ResolvedJvmProfile {
    param([Parameter(Mandatory = $true)][hashtable]$Config)
    $profile = [string]$Config.JvmProfile
    if ($profile -eq 'Auto') { $profile = if ((Get-PhysicalMemoryGB) -ge 32) { 'High32GB' } else { 'Standard16GB' } }
    if ($profile -notin @('Standard16GB', 'High32GB')) { throw "Unsupported JvmProfile: $profile" }
    return $profile
}

function Get-GpLinkHeapArguments {
    param([Parameter(Mandatory = $true)][ValidateSet('Standard16GB', 'High32GB')][string]$JvmProfile)
    if ($JvmProfile -eq 'High32GB') { return '-Xms1g -Xmx4g' }
    return '-Xms512m -Xmx2g'
}

function Get-GpLinkServiceAppParameters {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Config,
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][string]$ExternalConfigPath,
        [Parameter(Mandatory = $true)][ValidateSet('Standard16GB', 'High32GB')][string]$JvmProfile
    )
    $heapArguments = Get-GpLinkHeapArguments -JvmProfile $JvmProfile
    $configUri = (New-Object System.Uri($ExternalConfigPath)).AbsoluteUri
    return "$heapArguments -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar `"$JarPath`" --spring.profiles.active=default --spring.config.additional-location=`"$configUri`""
}

function ConvertTo-NssmArgumentTokens {
    param([AllowEmptyString()][string]$CommandLine)
    $tokens = New-Object System.Collections.Generic.List[string]
    $builder = New-Object System.Text.StringBuilder
    $inQuotes = $false
    foreach ($character in ([string]$CommandLine).ToCharArray()) {
        if ($character -eq '"') {
            $inQuotes = -not $inQuotes
            continue
        }
        if ([char]::IsWhiteSpace($character) -and -not $inQuotes) {
            if ($builder.Length -gt 0) {
                [void]$tokens.Add($builder.ToString())
                [void]$builder.Clear()
            }
            continue
        }
        [void]$builder.Append($character)
    }
    if ($inQuotes) { throw 'NSSM AppParameters contains an unmatched quote.' }
    if ($builder.Length -gt 0) { [void]$tokens.Add($builder.ToString()) }
    return @($tokens.ToArray())
}

function Get-OptionalObjectProperty {
    param(
        [AllowNull()]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Set-RestrictedAcl {
    param([Parameter(Mandatory = $true)][string]$Path, [switch]$Directory, [switch]$ExcludeLocalService)
    $inheritance = if ($Directory) { '(OI)(CI)' } else { '' }
    & icacls.exe $Path '/inheritance:r' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to disable inherited ACLs on $Path" }
    $grants = @("*S-1-5-18:${inheritance}F", "*S-1-5-32-544:${inheritance}F")
    if (-not $ExcludeLocalService) { $grants += "*S-1-5-19:${inheritance}RX" }
    & icacls.exe $Path '/grant:r' @grants | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to set restricted ACLs on $Path" }
}

function Get-PhysicalMemoryGB {
    $bytes = (Get-CimInstance Win32_PhysicalMemory -ErrorAction SilentlyContinue | Measure-Object -Property Capacity -Sum).Sum
    if ($null -eq $bytes -or [double]$bytes -le 0) { $bytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory }
    return [math]::Round(([double]$bytes / 1GB), 2)
}
