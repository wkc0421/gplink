$bundleRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$scriptsRoot = Join-Path $bundleRoot 'scripts'
$packageRoot = Join-Path $bundleRoot 'packages'
$manifestPath = Join-Path $bundleRoot 'manifest\packages.json'
$bundleMetadataPath = Join-Path $bundleRoot 'manifest\bundle.json'
. (Join-Path $scriptsRoot 'Common.ps1')

Describe 'GPLink2 offline bundle manifests' {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $metadata = Get-Content -LiteralPath $bundleMetadataPath -Raw | ConvertFrom-Json
    $requiredIds = @('application','protocol-modbus','java','postgresql','timescaledb','vcredist','openssl','nssm','baseline-sql')

    It 'uses one bundle ID in both manifests' {
        ([string]$metadata.bundleId -eq [string]$manifest.bundleId) | Should Be $true
    }

    It 'contains nine required package IDs with the optional Modbus protocol package' {
        @($manifest.packages).Count | Should Be 9
        foreach ($id in $requiredIds) { @($manifest.packages | Where-Object id -eq $id).Count | Should Be 1 }
        @($manifest.packages | Where-Object { $_.id -in @('protocol-khmqtt','protocol-mbrtu') }).Count | Should Be 0
    }

    It 'uses unique package filenames' {
        $names = @($manifest.packages | ForEach-Object { ([string]$_.file).ToLowerInvariant() })
        (($names | Select-Object -Unique).Count -eq $names.Count) | Should Be $true
    }

    It 'locks the package manifest hash in bundle metadata' {
        ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -eq [string]$metadata.packageManifestSha256) | Should Be $true
    }

    It 'has every package with the exact recorded size and hash' {
        foreach ($package in $manifest.packages) {
            $path = Join-Path $packageRoot ([string]$package.file)
            (Test-Path -LiteralPath $path -PathType Leaf) | Should Be $true
            ((Get-Item -LiteralPath $path).Length -eq [long]$package.size) | Should Be $true
            ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq ([string]$package.sha256).ToUpperInvariant()) | Should Be $true
        }
    }

    It 'validates the optional Modbus protocol JAR contents' {
        $protocol = Get-ManifestPackage -Manifest $manifest -Id 'protocol-modbus'
        { Test-ModbusProtocolJar -Path (Join-Path $packageRoot ([string]$protocol.file)) } | Should Not Throw
    }
}

Describe 'GPLink2 baseline package' {
    $sqlPath = Join-Path $packageRoot 'gplink-baseline.sql'
    $sql = [System.IO.File]::ReadAllText($sqlPath)

    It 'passes the dedicated fail-closed SQL validator' {
        { Test-BaselineSql -Path $sqlPath } | Should Not Throw
    }

    It 'contains only the two approved network resource IDs' {
        foreach ($value in @('2068932624811515904','2068926208973430784')) {
            $sql.Contains($value) | Should Be $true
        }
        ($sql.Contains('modbus-rtu.v1') -or $sql.Contains('jetlinks.v3.0')) | Should Be $false
    }

    It 'has no test-server address, remote URL, or access-key field' {
        (-not ($sql -match '(?i)192\.168\.5\.19|https?://|access[_-]?key')) | Should Be $true
    }

    It 'is transactional and refuses a second application' {
        ($sql.Contains('BEGIN;') -and $sql.Contains('COMMIT;') -and $sql.Contains('already been applied')) | Should Be $true
    }

    It 'does not import protocol rows or protocol JAR file records' {
        ($sql -match '(?im)^INSERT INTO dev_protocol\s' -or $sql -match '(?im)^INSERT INTO s_file\s') | Should Be $false
        $sql.Contains('DELETE FROM dev_protocol') | Should Be $true
    }

    It 'does not leave the build-time seed marker' {
        $sql.Contains('-- GPLINK_BASELINE_SEED') | Should Be $false
    }

    It 'marks the SQL package as sensitive' {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        [bool](Get-ManifestPackage -Manifest $manifest -Id 'baseline-sql').sensitive | Should Be $true
    }
}

Describe 'Installation integration and safety' {
    $install = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Install.ps1'))
    $import = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Import-GpLinkBaseline.ps1'))
    $service = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Install-GpLinkService.ps1'))
    $verify = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Verify.ps1'))
    $preflight = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Preflight.ps1'))
    $reinstall = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Reinstall.ps1'))

    It 'runs baseline import after service installation and before verification' {
        (($install.IndexOf('Install-GpLinkService.ps1') -lt $install.IndexOf('Import-GpLinkBaseline.ps1')) -and ($install.IndexOf('Import-GpLinkBaseline.ps1') -lt $install.IndexOf('Verify.ps1'))) | Should Be $true
    }

    It 'checks the baseline marker before stopping the service' {
        ($import.IndexOf('$markerExists') -lt $import.IndexOf('Stopping GPLink2')) | Should Be $true
    }

    It 'passes the SQL hash and bundle ID as psql variables' {
        ($import.Contains('baseline_sha256 = $baselineHash') -and $import.Contains('bundle_id = $bundleId')) | Should Be $true
    }

    It 'does not copy external protocol JARs into local application storage' {
        ($service.Contains('protocol-khmqtt.jar') -or $service.Contains('protocol-mbrtu.jar') -or $service.Contains('protocol-khmqtt') -or $service.Contains('protocol-mbrtu')) | Should Be $false
    }

    It 'uses only NSSM 2.24 settings' {
        $service.Contains("@('AppTimestampLog'") | Should Be $false
        ($service.Contains("@('AppRotateFiles', '1')") -and $service.Contains("@('AppRotateOnline', '1')") -and $service.Contains("@('AppRotateSeconds', '86400')") -and $service.Contains("@('AppRotateBytes', '10485760')")) | Should Be $true
        $verify.Contains('AppTimestampLog') | Should Be $false
    }

    It 'prefers the validated D-drive system JDK and installs bundled JDK only when needed' {
        $common = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Common.ps1'))
        ($common.Contains("D:\Program Files\Java\jdk-17") -and $common.Contains('function Get-PreferredSystemJdk') -and $common.Contains('javac.exe')) | Should Be $true
        ($service.Contains('$systemJdk = Get-PreferredSystemJdk') -and $service.Contains('if ($useBundledJava)') -and $service.Contains('$systemJdk.JavaPath')) | Should Be $true
        ($verify.Contains('javaRuntimeMode') -and $verify.Contains('javaRuntimePath') -and $verify.Contains('SystemJdk')) | Should Be $true
        ($preflight.Contains('java-runtime-selection') -and $preflight.Contains('will use it and skip bundled Java extraction') -and $preflight.Contains('bundled JDK will be installed')) | Should Be $true
        ($common.Contains('function Test-JdkArchive') -and $common.Contains('-RequireJdk')) | Should Be $true
        ($service.Contains("'bin\javac.exe'") -and $verify.Contains('BundledJdk')) | Should Be $true
    }

    It 'writes NSSM numeric settings as explicit registry DWORD values' {
        ($service.Contains('function Set-NssmRegistryDword') -and $service.Contains('RegistryValueKind]::DWord') -and $service.Contains('AppThrottle = 1500')) | Should Be $true
        $verify.Contains('is missing from the service registry') | Should Be $true
    }

    It 'writes the NSSM default exit action explicitly' {
        ($service.Contains('function Set-NssmRegistryString') -and $service.Contains("SubKeyName 'AppExit'") -and $service.Contains("-Name 'Default' -Value 'Restart'")) | Should Be $true
        $verify.Contains('default exit action is missing from the service registry') | Should Be $true
    }

    It 'normalizes NSSM command line quoting and probes optional login fields safely' {
        $expected = @(ConvertTo-NssmArgumentTokens -CommandLine '-jar "D:\Program Files\GpLink2\application.jar" --spring.config.additional-location="file:///D:/Program Files/GpLink2/config/application-deploy.yml"')
        $expected.Count | Should Be 3
        $expected[1] | Should Be 'D:\Program Files\GpLink2\application.jar'
        $expected[2] | Should Be '--spring.config.additional-location=file:///D:/Program Files/GpLink2/config/application-deploy.yml'
        ($verify.Contains('Get-OptionalObjectProperty -Object $response -Name ''success''') -and $verify.Contains('Get-OptionalObjectProperty -Object $response -Name ''result''')) | Should Be $true
    }

    It 'preflight validates the optional Modbus package while keeping protocol import manual' {
        ($preflight.Contains("'protocol-modbus'") -and $preflight.Contains('Test-ModbusProtocolJar') -and -not $preflight.Contains('protocol-khmqtt') -and -not $preflight.Contains('protocol-mbrtu')) | Should Be $true
    }

    It 'provides a double-click Modbus API import entry point' {
        $protocolScript = [System.IO.File]::ReadAllText((Join-Path $scriptsRoot 'Install-Protocol.ps1'))
        $cmd = [System.IO.File]::ReadAllText((Join-Path $bundleRoot 'install_protocol.cmd'))
        (Test-Path -LiteralPath (Join-Path $bundleRoot 'install_protocol.cmd') -PathType Leaf) | Should Be $true
        ($cmd.Contains('Install-Protocol.ps1') -and $protocolScript.Contains('modbus-rtu.v1') -and $protocolScript.Contains('org.jetlinks.protocol.modbus.ModbusProtocolSupportProvider') -and $protocolScript.Contains('/file/upload') -and $protocolScript.Contains('MultipartFormDataContent') -and $protocolScript.Contains('fileId = $fileId') -and $protocolScript.Contains('location = $location') -and $protocolScript.Contains('[AllowEmptyString()][string]$Token') -and $protocolScript.Contains("-Method Patch") -and $protocolScript.Contains("-Method Post")) | Should Be $true
        $install.Contains('Install-Protocol.ps1') | Should Be $false
    }

    It 'uses the configured six-character minimum for deployment secrets' {
        ($preflight.Contains('$value.Length -lt 6') -and $preflight.Contains('at least 6 characters')) | Should Be $true
    }

    It 'verifies the marker and exact network-only resource set' {
        ($verify.Contains('-Id ''resource-baseline''') -and $verify.Contains('gplink_deployment_baseline') -and $verify.Contains('0|0|0||2068926208973430784,2068932624811515904') -and -not $verify.Contains('protocol-load-{0}')) | Should Be $true
    }

    It 'does not contain PowerShell parse errors' {
        $parseErrors = @()
        Get-ChildItem -LiteralPath $scriptsRoot -Filter '*.ps1' -File | ForEach-Object {
            $tokens = $null
            $errors = $null
            [void][System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors)
            $parseErrors += @($errors)
        }
        $parseErrors.Count | Should Be 0
    }

    It 'provides an explicit destructive reinstall path without weakening normal install safety' {
        (Test-Path -LiteralPath (Join-Path $bundleRoot 'Reinstall-GpLink.cmd') -PathType Leaf) | Should Be $true
        ($reinstall.Contains("RESET GPLink2") -and $reinstall.Contains('Stop-AndDeleteReinstallService') -and $reinstall.Contains('Assert-ReinstallServiceOwnership') -and $reinstall.Contains("'-Reinstall'")) | Should Be $true
        ($reinstall.Contains('Remove-ReinstallDirectory') -and $reinstall.Contains('offline bundle or package directory')) | Should Be $true
        ($install.Contains('Re-running the complete read-only preflight')) | Should Be $true
        ($preflight.Contains('[switch]$Reinstall') -and $preflight.Contains('Destructive reinstall mode')) | Should Be $true
    }

    It 'does not recursively delete reparse-point trees during reinstall' {
        ($reinstall.Contains('Test-ReparsePointInTree') -and $reinstall.Contains('ReparsePoint') -and $reinstall.Contains('refusing recursive deletion') -and $reinstall.Contains('Assert-ReinstallDirectoryTarget')) | Should Be $true
        ($reinstall.Contains('Initialize-DeployLog -LogPath $logPath') -and $reinstall.Contains('Reinstall stopped:')) | Should Be $true
    }

    It 'parenthesizes both target-overlap calls in reinstall safety checks' {
        $reinstall.Contains("if ((Test-PathsOverlap -First `$fullPath -Second `$BundleRoot) -or (Test-PathsOverlap -First `$fullPath -Second `$PackageDirectory))") | Should Be $true
    }
}

Describe 'Configuration contract' {
    It 'recognizes supported Windows Server and Windows 11 builds' {
        (Get-SupportedWindowsServerRelease -ProductType 3 -BuildNumber 17763) | Should Be '2019'
        (Get-SupportedWindowsServerRelease -ProductType 3 -BuildNumber 20348) | Should Be '2022'
        (Get-SupportedWindowsServerRelease -ProductType 3 -BuildNumber 26100) | Should Be '2025'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 22000) | Should Be '11 21H2'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 22621) | Should Be '11 22H2'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 22631) | Should Be '11 23H2'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 26100) | Should Be '11 24H2'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 26200) | Should Be '11 25H2'
        (Get-SupportedWindowsServerRelease -ProductType 1 -BuildNumber 19045) | Should Be $null
    }

    It 'accepts the example PSD1 through the same schema validator used by preflight' {
        $example = Read-DeployConfig -ConfigPath (Join-Path $bundleRoot 'config\deploy-config.example.psd1')
        { Assert-DeployConfigValid -Config $example } | Should Not Throw
    }

    It 'uses the default passwordless local Redis configuration' {
        $example = Read-DeployConfig -ConfigPath (Join-Path $bundleRoot 'config\deploy-config.example.psd1')
        ([string]::IsNullOrEmpty([string]$example.RedisPassword) -and [bool]$example.AllowPasswordlessRedis -and [string]$example.RedisHost -eq '127.0.0.1') | Should Be $true
    }

    It 'keeps the real config and sensitive media ignored by Git' {
        $ignore = [System.IO.File]::ReadAllText((Join-Path $bundleRoot '.gitignore'))
        ($ignore.Contains('/config/deploy-config.psd1') -and $ignore.Contains('/packages/*') -and $ignore.Contains('/GpLink2-Offline-*.zip')) | Should Be $true
    }
}

Describe 'Double-click ZIP verifier' {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $zipPath = Join-Path $bundleRoot ("GpLink2-Offline-$($manifest.bundleId).zip")
    $verifierPath = Join-Path $bundleRoot 'Verify-GpLink2-Package.cmd'

    It 'is generated beside the ZIP with the fixed filename and hash' {
        (Test-Path -LiteralPath $verifierPath -PathType Leaf) | Should Be $true
        $text = [System.IO.File]::ReadAllText($verifierPath)
        $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
        ($text.Contains([System.IO.Path]::GetFileName($zipPath)) -and $text.Contains($zipHash)) | Should Be $true
    }

    It 'is not incorrectly embedded inside the ZIP' {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
        try { ($null -eq $archive.GetEntry('GpLink2-Offline\Verify-GpLink2-Package.cmd')) | Should Be $true }
        finally { $archive.Dispose() }
    }

    It 'validates the current ZIP successfully without typed commands' {
        $oldNoPause = $env:GPLINK_NO_PAUSE
        try {
            $env:GPLINK_NO_PAUSE = '1'
            $output = @(& cmd.exe /d /c ('"' + $verifierPath + '"') 2>&1)
            $exitCode = $LASTEXITCODE
        } finally {
            $env:GPLINK_NO_PAUSE = $oldNoPause
        }
        $exitCode | Should Be 0
        (($output -join [Environment]::NewLine).Contains('[PASS]')) | Should Be $true
    }
}
