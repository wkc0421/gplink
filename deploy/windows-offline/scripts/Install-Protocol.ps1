[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [string]$ApiBaseUrl
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$context = $null
$config = $null
$logPath = $null
$reportPath = $null
$textReportPath = $null
$protocolId = 'modbus-rtu.v1'
$providerClass = 'org.jetlinks.protocol.modbus.ModbusProtocolSupportProvider'
$packageVersion = $null
$packageHash = $null
$fileId = $null
$action = $null

function Get-ApiEnvelopeValue {
    param([AllowNull()]$Object)
    if ($null -eq $Object) { return $null }
    foreach ($name in @('result', 'data')) {
        $value = Get-OptionalObjectProperty -Object $Object -Name $name
        if ($null -ne $value) { return $value }
    }
    return $Object
}

function Get-ApiToken {
    param([AllowNull()]$Response)
    $successValue = Get-OptionalObjectProperty -Object $Response -Name 'success'
    if ($null -ne $successValue -and -not [bool]$successValue) { throw 'Login API reported failure.' }
    if ($Response -is [string]) { return [string]$Response }
    foreach ($candidate in @(
        (Get-OptionalObjectProperty -Object $Response -Name 'result'),
        (Get-OptionalObjectProperty -Object $Response -Name 'data'),
        $Response
    )) {
        if ($null -eq $candidate) { continue }
        if ($candidate -is [string]) { $tokenCandidate = [string]$candidate }
        else { $tokenCandidate = Get-OptionalObjectProperty -Object $candidate -Name 'token' }
        if (-not [string]::IsNullOrWhiteSpace([string]$tokenCandidate)) { return [string]$tokenCandidate }
    }
    throw 'Login API response did not contain a token.'
}

function Invoke-ApiJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Get', 'Post', 'Patch')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Token,
        [string]$Body
    )
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) { $headers['X-Access-Token'] = $Token }
    try {
        if ([string]::IsNullOrWhiteSpace($Body)) {
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 60
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -ContentType 'application/json' -Body $Body -TimeoutSec 60
    } catch {
        throw "API request failed ($Method $([System.Uri]$Uri)): HTTP request was not successful."
    }
}

function Get-ApiProtocolOrNull {
    param([Parameter(Mandatory = $true)][string]$Uri, [Parameter(Mandatory = $true)][string]$Token)
    $headers = @{ 'X-Access-Token' = $Token }
    try {
        return [pscustomobject]@{ Found = $true; Value = (Invoke-RestMethod -Method Get -Uri $Uri -Headers $headers -TimeoutSec 60) }
    } catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) {
            try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { $statusCode = $null }
        }
        if ($statusCode -eq 404) { return [pscustomobject]@{ Found = $false; Value = $null } }
        throw "Could not query the existing Modbus protocol record (HTTP $statusCode)."
    }
}

function Upload-ProtocolJar {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$BaseUrl, [Parameter(Mandatory = $true)][string]$Token)
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $stream = $null
    $fileContent = $null
    $multipart = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(120)
        [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('X-Access-Token', $Token)
        $stream = [System.IO.File]::OpenRead($Path)
        $fileContent = New-Object System.Net.Http.StreamContent($stream)
        $fileContent.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/java-archive')
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        [void]$multipart.Add($fileContent, 'file', [System.IO.Path]::GetFileName($Path))
        $response = $client.PostAsync(($BaseUrl.TrimEnd('/') + '/file/upload'), $multipart).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "File upload API returned HTTP $([int]$response.StatusCode)." }
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ([string]::IsNullOrWhiteSpace($body)) { throw 'File upload API returned an empty response.' }
        return (ConvertFrom-Json -InputObject $body)
    } finally {
        if ($null -ne $multipart) { $multipart.Dispose() }
        if ($null -ne $fileContent) { $fileContent.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
        if ($null -ne $client) { $client.Dispose() }
    }
}

function Resolve-UploadedLocation {
    param([Parameter(Mandatory = $true)]$FileInfo, [Parameter(Mandatory = $true)][string]$BaseUrl, [Parameter(Mandatory = $true)][string]$UploadedId)
    $location = [string](Get-OptionalObjectProperty -Object $FileInfo -Name 'accessUrl')
    $others = Get-OptionalObjectProperty -Object $FileInfo -Name 'others'
    $accessKey = [string](Get-OptionalObjectProperty -Object $others -Name 'accessKey')
    # Keep the server-returned relative accessUrl relative. The GPLink2
    # loader then prefers fileId and resolves the uploaded file internally,
    # avoiding an unauthenticated HTTP round-trip to its own /file endpoint.
    if ([string]::IsNullOrWhiteSpace($location)) { $location = '/file/' + [Uri]::EscapeDataString($UploadedId) }
    if (-not [string]::IsNullOrWhiteSpace($accessKey) -and $location -notmatch '(?i)[?&]accessKey=') {
        $separator = if ($location.Contains('?')) { '&' } else { '?' }
        $location += $separator + 'accessKey=' + [Uri]::EscapeDataString($accessKey)
    }
    return $location
}

function Get-TransportCount {
    param([AllowNull()]$Response)
    $value = Get-ApiEnvelopeValue -Object $Response
    if ($value -is [System.Collections.IEnumerable] -and $value -isnot [string]) { return @($value).Count }
    foreach ($name in @('transports', 'items', 'content')) {
        $nested = Get-OptionalObjectProperty -Object $value -Name $name
        if ($null -ne $nested) { return @($nested).Count }
    }
    if ($null -ne $value) { return 1 }
    return 0
}

try {
    $context = Get-DeploymentContext -ConfigPath $ConfigPath
    $config = $context.Config
    $logDirectory = Join-Path $context.BundleRoot 'logs'
    $logPath = if (-not [string]::IsNullOrWhiteSpace($env:GPLINK_DEPLOY_LOG_PATH)) { $env:GPLINK_DEPLOY_LOG_PATH } else { Join-Path $logDirectory ('install-protocol-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    $reportPath = Join-Path $logDirectory 'install-protocol-report.json'
    $textReportPath = Join-Path $logDirectory 'install-protocol-report.txt'
    Initialize-DeployLog -LogPath $logPath -Config $config
    if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) { $ApiBaseUrl = "http://127.0.0.1:$([int]$config.ServerPort)" }
    $ApiBaseUrl = $ApiBaseUrl.TrimEnd('/')
    if (-not ([Uri]::IsWellFormedUriString($ApiBaseUrl, [UriKind]::Absolute))) { throw 'ApiBaseUrl must be an absolute HTTP URL.' }
    $protocolPackage = Get-ManifestPackage -Manifest $context.Manifest -Id 'protocol-modbus'
    $packageResult = Test-PackageFile -Package $protocolPackage -PackageDirectory $context.PackageDirectory -RequireAuthenticode $true
    Test-ModbusProtocolJar -Path $packageResult.Path
    $packageVersion = [string]$protocolPackage.version
    $packageHash = [string]$packageResult.Hash
    Write-DeployLog -Message "Validated Modbus protocol package version $packageVersion ($packageHash)."

    $loginPayload = @{ username = [string]$config.AdminUsername; password = [string]$config.AdminUserPassword; expires = 3600000 } | ConvertTo-Json -Compress
    $token = Get-ApiToken -Response (Invoke-ApiJson -Method Post -Uri ($ApiBaseUrl + '/authorize/login') -Token '' -Body $loginPayload)
    Write-DeployLog -Message 'Authenticated to the local GPLink2 API; the token will not be written to logs.'

    $uploadResponse = Upload-ProtocolJar -Path $packageResult.Path -BaseUrl $ApiBaseUrl -Token $token
    $fileInfo = Get-ApiEnvelopeValue -Object $uploadResponse
    $fileId = [string](Get-OptionalObjectProperty -Object $fileInfo -Name 'id')
    if ([string]::IsNullOrWhiteSpace($fileId)) { throw 'File upload API response did not contain a file id.' }
    $location = Resolve-UploadedLocation -FileInfo $fileInfo -BaseUrl $ApiBaseUrl -UploadedId $fileId
    Write-DeployLog -Message 'Uploaded protocol JAR to the GPLink2 file service.'

    $protocolPayload = [ordered]@{
        id = $protocolId
        name = 'Modbus RTU'
        description = 'Modbus RTU over TCP protocol'
        type = 'jar'
        state = 1
        configuration = [ordered]@{
            fileId = $fileId
            location = $location
            provider = $providerClass
            fileName = [string]$protocolPackage.file
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $existing = Get-ApiProtocolOrNull -Uri ($ApiBaseUrl + '/protocol/' + [Uri]::EscapeDataString($protocolId)) -Token $token
    if ($existing.Found) {
        [void](Invoke-ApiJson -Method Patch -Uri ($ApiBaseUrl + '/protocol') -Token $token -Body $protocolPayload)
        $action = 'updated'
    } else {
        [void](Invoke-ApiJson -Method Post -Uri ($ApiBaseUrl + '/protocol') -Token $token -Body $protocolPayload)
        $action = 'created'
    }
    Write-DeployLog -Message "Protocol record $action with fixed id $protocolId."

    $detailResponse = Invoke-ApiJson -Method Get -Uri ($ApiBaseUrl + '/protocol/' + [Uri]::EscapeDataString($protocolId)) -Token $token
    $detail = Get-ApiEnvelopeValue -Object $detailResponse
    $detailId = [string](Get-OptionalObjectProperty -Object $detail -Name 'id')
    $detailType = [string](Get-OptionalObjectProperty -Object $detail -Name 'type')
    $detailConfiguration = Get-OptionalObjectProperty -Object $detail -Name 'configuration'
    $detailProvider = [string](Get-OptionalObjectProperty -Object $detailConfiguration -Name 'provider')
    $detailFileId = [string](Get-OptionalObjectProperty -Object $detailConfiguration -Name 'fileId')
    if ($detailId -ne $protocolId -or $detailType -ne 'jar' -or $detailProvider -ne $providerClass -or $detailFileId -ne $fileId) {
        throw 'Protocol API did not persist the fixed id, jar type, provider, and uploaded file id as expected.'
    }

    $transportCount = 0
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $transportResponse = Invoke-ApiJson -Method Get -Uri ($ApiBaseUrl + '/protocol/' + [Uri]::EscapeDataString($protocolId) + '/transports') -Token $token
        $transportCount = Get-TransportCount -Response $transportResponse
        if ($transportCount -gt 0) { break }
        Start-Sleep -Seconds 2
    }
    if ($transportCount -le 0) { throw 'Modbus protocol record exists, but no supported transports were exposed by the API.' }
    Write-DeployLog -Message "Modbus protocol imported and loaded (id $protocolId; $transportCount transport(s))."

    $report = [ordered]@{ schemaVersion = 1; generatedAt = (Get-Date).ToUniversalTime().ToString('o'); overallPassed = $true; protocolId = $protocolId; packageVersion = $packageVersion; jarSha256 = $packageHash; fileId = $fileId; action = $action; transportCount = $transportCount }
    Write-JsonFile -InputObject $report -Path $reportPath
    Set-Content -LiteralPath $textReportPath -Value @('GPLink2 Modbus protocol import', ('Generated: ' + $report.generatedAt), 'Overall: PASS', ('Protocol ID: ' + $protocolId), ('Package version: ' + $packageVersion), ('JAR SHA-256: ' + $packageHash), ('Action: ' + $action), ('Transport count: ' + $transportCount)) -Encoding UTF8
    Write-DeployLog -Message "Modbus protocol import completed. Reports: $reportPath and $textReportPath"
    exit 0
} catch {
    $safeError = Protect-LogText -Text $_.Exception.Message
    if ($null -ne $logPath -and [string]::IsNullOrWhiteSpace($script:DeployLogPath)) { Initialize-DeployLog -LogPath $logPath -Config $config }
    Write-DeployLog -Level 'ERROR' -Message "Modbus protocol import failed: $safeError"
    if ($null -ne $reportPath) {
        $failureReport = [ordered]@{ schemaVersion = 1; generatedAt = (Get-Date).ToUniversalTime().ToString('o'); overallPassed = $false; protocolId = $protocolId; packageVersion = $packageVersion; jarSha256 = $packageHash; fileId = $fileId; action = $action; error = $safeError }
        Write-JsonFile -InputObject $failureReport -Path $reportPath
        Set-Content -LiteralPath $textReportPath -Value @('GPLink2 Modbus protocol import', ('Generated: ' + $failureReport.generatedAt), 'Overall: FAIL', ('Error: ' + $safeError)) -Encoding UTF8
    }
    exit 1
}
