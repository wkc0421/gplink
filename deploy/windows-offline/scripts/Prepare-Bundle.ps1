[CmdletBinding()]
param(
    [string]$ApplicationJarPath,
    [switch]$Download,
    [string]$OutputZip
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$bundleRoot = Get-BundleRoot
$packageDirectory = Join-Path $bundleRoot 'packages'
$manifestPath = Join-Path $bundleRoot 'manifest\packages.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$exampleConfigPath = Join-Path $bundleRoot 'config\deploy-config.example.psd1'
$exampleConfig = Read-DeployConfig -ConfigPath $exampleConfigPath
Assert-DeployConfigValid -Config $exampleConfig
New-Item -ItemType Directory -Path $packageDirectory -Force | Out-Null

if ([string]::IsNullOrWhiteSpace($ApplicationJarPath)) {
    $repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $bundleRoot '..\..'))
    $ApplicationJarPath = Join-Path $repositoryRoot 'back\jetlinks-community-2.10\jetlinks-community-2.10\jetlinks-standalone\target\application.jar'
}
$ApplicationJarPath = [System.IO.Path]::GetFullPath($ApplicationJarPath)
if (-not (Test-Path -LiteralPath $ApplicationJarPath -PathType Leaf)) { throw "Application JAR not found: $ApplicationJarPath" }
$applicationPackage = Get-ManifestPackage -Manifest $manifest -Id 'application'
$sourceApplicationItem = Get-Item -LiteralPath $ApplicationJarPath
$sourceApplicationHash = (Get-FileHash -LiteralPath $ApplicationJarPath -Algorithm SHA256).Hash
if ([string]$applicationPackage.sha256 -match '^[A-Fa-f0-9]{64}$' -and $sourceApplicationHash -ne ([string]$applicationPackage.sha256).ToUpperInvariant()) {
    throw "Source application JAR hash is $sourceApplicationHash, expected $($applicationPackage.sha256). The fixed package directory was not modified."
}
if ([long]$applicationPackage.size -gt 0 -and $sourceApplicationItem.Length -ne [long]$applicationPackage.size) {
    throw "Source application JAR size is $($sourceApplicationItem.Length), expected $($applicationPackage.size). The fixed package directory was not modified."
}
Test-JarContent -Path $ApplicationJarPath
$applicationDestination = [System.IO.Path]::GetFullPath((Join-Path $packageDirectory ([string]$applicationPackage.file)))
if (-not [string]::Equals($ApplicationJarPath, $applicationDestination, [System.StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $ApplicationJarPath -Destination $applicationDestination -Force
}

function Save-RemoteFile {
    param([Parameter(Mandatory = $true)][string]$Uri, [Parameter(Mandatory = $true)][string]$Destination)
    $temporary = $Destination + '.download-' + [guid]::NewGuid().ToString('N')
    try {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($null -ne $curl) {
            & $curl.Source '--location' '--fail' '--silent' '--show-error' '--retry' '3' '--output' $temporary $Uri
            if ($LASTEXITCODE -ne 0) { throw "curl download failed with exit code $LASTEXITCODE." }
        } else {
            $client = New-Object System.Net.WebClient
            try { $client.DownloadFile($Uri, $temporary) } finally { $client.Dispose() }
        }
        if (-not (Test-Path -LiteralPath $temporary -PathType Leaf) -or (Get-Item -LiteralPath $temporary).Length -le 0) { throw 'Downloaded file is empty.' }
        Move-Item -LiteralPath $temporary -Destination $Destination -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

if ($Download) {
    foreach ($package in $manifest.packages) {
        if ([string]$package.id -eq 'application') { continue }
        $destination = Join-Path $packageDirectory ([string]$package.file)
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            Write-Host "[KEEP] $($package.file)"
            continue
        }
        if ([string]::IsNullOrWhiteSpace([string]$package.sourceUrl)) { throw "No sourceUrl is configured for $($package.id)." }
        Write-Host "[DOWNLOAD] $($package.sourceUrl)"
        $archiveMember = ''
        if ($package.PSObject.Properties.Name -contains 'archiveMember') { $archiveMember = [string]$package.archiveMember }
        if (-not [string]::IsNullOrWhiteSpace($archiveMember)) {
            $sourceArchive = Join-Path ([System.IO.Path]::GetTempPath()) ('gplink-source-' + [guid]::NewGuid().ToString('N') + '.zip')
            $extractDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('gplink-source-' + [guid]::NewGuid().ToString('N'))
            try {
                Save-RemoteFile -Uri ([string]$package.sourceUrl) -Destination $sourceArchive
                New-Item -ItemType Directory -Path $extractDirectory -Force | Out-Null
                [System.IO.Compression.ZipFile]::ExtractToDirectory($sourceArchive, $extractDirectory)
                $memberPath = Join-Path $extractDirectory ($archiveMember.Replace('/', '\'))
                if (-not (Test-Path -LiteralPath $memberPath -PathType Leaf)) { throw "Archive member not found: $archiveMember" }
                Copy-Item -LiteralPath $memberPath -Destination $destination -Force
            } finally {
                if (Test-Path -LiteralPath $sourceArchive) { Remove-Item -LiteralPath $sourceArchive -Force }
                if (Test-Path -LiteralPath $extractDirectory) { Remove-Item -LiteralPath $extractDirectory -Recurse -Force }
            }
        } else {
            Save-RemoteFile -Uri ([string]$package.sourceUrl) -Destination $destination
        }
    }
}

foreach ($package in $manifest.packages) {
    $path = Join-Path $packageDirectory ([string]$package.file)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required package is missing: $($package.file). Use -Download or copy it into packages." }
    $item = Get-Item -LiteralPath $path
    if ($item.Length -le 0) { throw "Package is empty: $($package.file)" }
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    $expectedHash = [string]$package.sha256
    if ($expectedHash -match '^[A-Fa-f0-9]{64}$' -and $actualHash -ne $expectedHash.ToUpperInvariant()) {
        throw "Downloaded or supplied file does not match the fixed hash for $($package.file)."
    }
    if ([bool]$package.authenticode) {
        $signature = Get-AuthenticodeSignature -LiteralPath $path
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) { throw "Invalid Authenticode signature for $($package.file): $($signature.Status)" }
    }
    if ([string]$package.id -eq 'vcredist' -and [string]$package.version -match '(?i)current') {
        $productVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($path).ProductVersion
        if ([string]::IsNullOrWhiteSpace($productVersion)) { throw 'Could not determine the Visual C++ runtime product version.' }
        $package.version = $productVersion
    }
    $package.sha256 = $actualHash
    $package.size = [long]$item.Length
    Write-Host ("[VERIFIED] {0}  {1}  {2} bytes" -f $package.file, $actualHash, $item.Length)
}

Test-JarContent -Path (Join-Path $packageDirectory ([string]$applicationPackage.file))
$javaPackage = Get-ManifestPackage -Manifest $manifest -Id 'java'
Test-JdkArchive -Path (Join-Path $packageDirectory ([string]$javaPackage.file)) -ExpectedVersion ([string]$javaPackage.version)
$timescalePackage = Get-ManifestPackage -Manifest $manifest -Id 'timescaledb'
Test-TimescaleArchive -Path (Join-Path $packageDirectory ([string]$timescalePackage.file)) -Version ([string]$timescalePackage.version) -PostgresMajor ([int]$timescalePackage.postgresMajor)
$baselinePackage = Get-ManifestPackage -Manifest $manifest -Id 'baseline-sql'
Test-BaselineSql -Path (Join-Path $packageDirectory ([string]$baselinePackage.file))
$protocolPackage = Get-ManifestPackage -Manifest $manifest -Id 'protocol-modbus'
Test-ModbusProtocolJar -Path (Join-Path $packageDirectory ([string]$protocolPackage.file))

Write-Utf8NoBomFile -Path $manifestPath -Content (($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine)
$bundleMetadataPath = Join-Path $bundleRoot 'manifest\bundle.json'
$bundleMetadata = Get-Content -LiteralPath $bundleMetadataPath -Raw | ConvertFrom-Json
if ($bundleMetadata.PSObject.Properties.Name -contains 'packageManifestSha256') {
    $bundleMetadata.packageManifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
} else {
    $bundleMetadata | Add-Member -NotePropertyName packageManifestSha256 -NotePropertyValue ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash)
}
Write-Utf8NoBomFile -Path $bundleMetadataPath -Content (($bundleMetadata | ConvertTo-Json -Depth 8) + [Environment]::NewLine)

$realConfig = Join-Path $bundleRoot 'config\deploy-config.psd1'
if (-not (Test-Path -LiteralPath $realConfig)) {
    Copy-Item -LiteralPath (Join-Path $bundleRoot 'config\deploy-config.example.psd1') -Destination $realConfig
    Write-Host '[CONFIG] Created config\deploy-config.psd1 from the example. Replace all placeholders before preflight.'
}

if ([string]::IsNullOrWhiteSpace($OutputZip)) {
    $OutputZip = Join-Path $bundleRoot ("GpLink2-Offline-$($manifest.bundleId).zip")
}
$OutputZip = [System.IO.Path]::GetFullPath($OutputZip)
if (Test-Path -LiteralPath $OutputZip) { Remove-Item -LiteralPath $OutputZip -Force }
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('gplink-bundle-stage-' + [guid]::NewGuid().ToString('N'))
$stageBundle = Join-Path $stageRoot 'GpLink2-Offline'
try {
    New-Item -ItemType Directory -Path $stageBundle -Force | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $bundleRoot -Force) {
        if ($item.Name -in @('logs', 'tests', 'Verify-GpLink2-Package.cmd') -or $item.Name -like 'Verify-GpLink2-Package-*.cmd') { continue }
        if ($item.FullName -eq $OutputZip) { continue }
        if ($item.Name -like '*-patch-*' -or $item.Name -like 'GpLink2-Offline-*.zip' -or $item.Name -like 'GpLink2-Offline-*.zip.sha256') { continue }
        if ($item.Name -eq 'packages') {
            $stagePackages = Join-Path $stageBundle 'packages'
            New-Item -ItemType Directory -Path $stagePackages -Force | Out-Null
            $packageReadme = Join-Path $item.FullName 'README.md'
            if (Test-Path -LiteralPath $packageReadme) { Copy-Item -LiteralPath $packageReadme -Destination $stagePackages }
            foreach ($package in $manifest.packages) {
                Copy-Item -LiteralPath (Join-Path $item.FullName ([string]$package.file)) -Destination $stagePackages
            }
        } else {
            Copy-Item -LiteralPath $item.FullName -Destination $stageBundle -Recurse -Force
        }
    }
    $stagedSecretConfig = Join-Path $stageBundle 'config\deploy-config.psd1'
    if (Test-Path -LiteralPath $stagedSecretConfig) { Remove-Item -LiteralPath $stagedSecretConfig -Force }
    Copy-Item -LiteralPath (Join-Path $stageBundle 'config\deploy-config.example.psd1') -Destination $stagedSecretConfig
    New-Item -ItemType Directory -Path (Join-Path $stageBundle 'logs') -Force | Out-Null
    Compress-Archive -Path $stageBundle -DestinationPath $OutputZip -CompressionLevel Optimal
} finally {
    if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
}
$zipHash = (Get-FileHash -LiteralPath $OutputZip -Algorithm SHA256).Hash
$checksumPath = $OutputZip + '.sha256'
Write-Utf8NoBomFile -Path $checksumPath -Content ("$zipHash *$([System.IO.Path]::GetFileName($OutputZip))" + [Environment]::NewLine)
$verifierPath = Join-Path (Split-Path -Parent $OutputZip) 'Verify-GpLink2-Package.cmd'
$verifierTemplate = @'
@echo off
setlocal
cd /d "%~dp0"
title GPLink2 Offline Package Verification

set "GPLINK_ZIP=__ZIP_FILE__"
set "GPLINK_EXPECTED=__ZIP_SHA256__"

echo Verifying the GPLink2 offline package. Please wait...
echo.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; try { $zipPath=Join-Path (Get-Location) $env:GPLINK_ZIP; $sidecarPath=$zipPath+'.sha256'; if(-not (Test-Path -LiteralPath $zipPath -PathType Leaf)){throw ('ZIP file not found: '+$env:GPLINK_ZIP)}; if(-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)){throw ('SHA-256 sidecar not found: '+$env:GPLINK_ZIP+'.sha256')}; $match=[regex]::Match([IO.File]::ReadAllText($sidecarPath),'(?i)\b[0-9a-f]{64}\b'); if(-not $match.Success){throw 'The SHA-256 sidecar format is invalid.'}; $sidecarHash=$match.Value.ToUpperInvariant(); if($sidecarHash -ne $env:GPLINK_EXPECTED){throw 'The SHA-256 sidecar does not belong to this release.'}; $actual=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToUpperInvariant(); if($actual -ne $env:GPLINK_EXPECTED){throw ('ZIP hash mismatch. Actual SHA-256: '+$actual)}; Write-Host '[PASS] ZIP integrity and SHA-256 verification succeeded.' -ForegroundColor Green; Write-Host ('File: '+$env:GPLINK_ZIP); Write-Host ('SHA-256: '+$actual); exit 0 } catch { Write-Host ('[FAIL] '+$_.Exception.Message) -ForegroundColor Red; Write-Host 'Copy the ZIP, sidecar, and verifier again before installing.'; exit 1 }"
set "GPLINK_EXIT=%ERRORLEVEL%"
echo.
if "%GPLINK_EXIT%"=="0" (
    echo You may extract the ZIP and run Check-GpLink.cmd.
) else (
    echo Do not extract or install this ZIP.
)
echo.
if not "%GPLINK_NO_PAUSE%"=="1" pause
exit /b %GPLINK_EXIT%
'@
$verifierContent = $verifierTemplate.Replace('__ZIP_FILE__', [System.IO.Path]::GetFileName($OutputZip)).Replace('__ZIP_SHA256__', $zipHash)
Write-Utf8NoBomFile -Path $verifierPath -Content ($verifierContent + [Environment]::NewLine)
Write-Host "[READY] Offline bundle: $OutputZip"
Write-Host "[READY] Bundle checksum: $checksumPath"
Write-Host "[READY] Double-click verifier: $verifierPath"
