[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

function Get-ReinstallServiceInfo {
    param([Parameter(Mandatory = $true)][string]$Name)
    $service = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $Name.Replace("'", "''")) -ErrorAction SilentlyContinue
    if ($null -eq $service) { return $null }
    [pscustomobject]@{
        Name = [string]$service.Name
        State = [string]$service.State
        PathName = [string]$service.PathName
        StartName = [string]$service.StartName
    }
}

function Assert-ReinstallTargetPath {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BundleRoot,
        [Parameter(Mandatory = $true)][string]$PackageDirectory
    )
    $fullPath = Assert-SafeAbsolutePath -Name $Name -Path $Path
    if ((Test-PathsOverlap -First $fullPath -Second $BundleRoot) -or (Test-PathsOverlap -First $fullPath -Second $PackageDirectory)) {
        throw "$Name cannot overlap the offline bundle or package directory: $fullPath"
    }
    return $fullPath
}

function Test-ReparsePointInTree {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $root = Get-Item -LiteralPath $Path -Force
    if (($root.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
    $children = Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop
    foreach ($child in $children) {
        if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
    }
    return $false
}

function Test-ReinstallAppDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return $true }
    $requiredMarkers = @(
        (Join-Path $Path 'application.jar'),
        (Join-Path $Path 'nssm.exe')
    )
    $present = @($requiredMarkers | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($present.Count -eq 0) {
        $first = Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop | Select-Object -First 1
        return ($null -eq $first)
    }
    # A valid installation may use the system JDK at D:\Program Files\Java\jdk-17
    # and therefore have no bundle-managed runtime under AppRoot.
    return $present.Count -eq $requiredMarkers.Count
}

function Test-ReinstallPostgresInstallDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return $true }
    $markers = @((Join-Path $Path 'bin\pg_ctl.exe'), (Join-Path $Path 'bin\pg_config.exe'))
    $present = @($markers | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($present.Count -eq 0) {
        $first = Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop | Select-Object -First 1
        return ($null -eq $first)
    }
    return $present.Count -eq $markers.Count
}

function Test-ReinstallPostgresDataDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return $true }
    $first = Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop | Select-Object -First 1
    if ($null -eq $first) { return $true }
    return (Test-Path -LiteralPath (Join-Path $Path 'PG_VERSION') -PathType Leaf)
}

function Assert-ReinstallDirectoryTarget {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        throw "$Name is an existing file, not a directory; refusing deletion: $Path"
    }
}

function Assert-ReinstallStateOwnership {
    param(
        [Parameter(Mandatory = $true)][string]$StatePath,
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$AppRoot,
        [Parameter(Mandatory = $true)][string]$PgInstallRoot,
        [Parameter(Mandatory = $true)][string]$PgData
    )
    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) { return }
    try {
        $state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
    } catch {
        throw "Existing install-state.json is not valid JSON; refusing to delete it: $StatePath"
    }
    foreach ($pair in @(
        @('appServiceName', [string]$Config.AppServiceName),
        @('pgServiceName', [string]$Config.PgServiceName),
        @('appRoot', $AppRoot),
        @('pgInstallRoot', $PgInstallRoot),
        @('pgData', $PgData)
    )) {
        $property = [string]$pair[0]
        if ($state.PSObject.Properties.Name -notcontains $property) { throw "Existing install state is missing '$property'; refusing destructive cleanup." }
        $actual = [string]$state.$property
        if ($property -match 'Root|Data') { $actual = [IO.Path]::GetFullPath($actual) }
        $expected = [string]$pair[1]
        if ($property -match 'Root|Data') { $expected = [IO.Path]::GetFullPath($expected) }
        if (-not [string]::Equals($actual, $expected, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Existing install state '$property' does not match configured target; refusing destructive cleanup."
        }
    }
}

function Assert-ReinstallServiceOwnership {
    param(
        [Parameter(Mandatory = $true)]$Service,
        [Parameter(Mandatory = $true)][string]$ExpectedExecutable,
        [string]$ExpectedDataDirectory
    )
    $executable = Get-WindowsServiceExecutablePath -PathName $Service.PathName
    if (-not [string]::Equals($executable, [IO.Path]::GetFullPath($ExpectedExecutable), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Service '$($Service.Name)' points to '$executable', not the configured GPLink2 target; refusing deletion."
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedDataDirectory)) {
        $dataDirectory = Get-WindowsCommandLineArgumentValue -CommandLine $Service.PathName -ArgumentName '-D'
        if ([string]::IsNullOrWhiteSpace($dataDirectory) -or
            -not [string]::Equals([IO.Path]::GetFullPath($dataDirectory), [IO.Path]::GetFullPath($ExpectedDataDirectory), [StringComparison]::OrdinalIgnoreCase)) {
            throw "Service '$($Service.Name)' does not use the configured PostgreSQL data directory; refusing deletion."
        }
    }
}

function Stop-AndDeleteReinstallService {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )
    $service = Get-ReinstallServiceInfo -Name $Name
    if ($null -eq $service) { return }
    Write-DeployLog -Message "Stopping service '$Name'."
    if ($service.State -ne 'Stopped') {
        Stop-Service -Name $Name -Force
        (Get-Service -Name $Name).WaitForStatus('Stopped', [TimeSpan]::FromSeconds($TimeoutSeconds))
    }
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -notin @(0, 1060)) { throw "Deleting service '$Name' failed with exit code $LASTEXITCODE." }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -eq (Get-ReinstallServiceInfo -Name $Name)) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Service '$Name' is still present after deletion was requested."
}

function Remove-ReinstallDirectory {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    if (Test-ReparsePointInTree -Path $Path) { throw "$Name contains a junction or reparse point; refusing recursive deletion: $Path" }
    Write-DeployLog -Message "Removing ${Name}: $Path"
    Remove-Item -LiteralPath $Path -Recurse -Force
    if (Test-Path -LiteralPath $Path) { throw "$Name was not removed: $Path" }
}

$bundleRoot = Get-BundleRoot
$logPath = Join-Path (Join-Path $bundleRoot 'logs') ('reinstall-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
Initialize-DeployLog -LogPath $logPath
$env:GPLINK_DEPLOY_LOG_PATH = $logPath
$context = $null
$config = $null
try {
    $context = Get-DeploymentContext -ConfigPath $ConfigPath
    $config = $context.Config
    Initialize-DeployLog -LogPath $logPath -Config $config
    if (-not (Test-IsAdministrator)) { throw 'Reinstall.ps1 must run as administrator.' }
    $appRoot = Assert-ReinstallTargetPath -Name 'AppRoot' -Path ([string]$config.AppRoot) -BundleRoot $context.BundleRoot -PackageDirectory $context.PackageDirectory
    $pgInstallRoot = Assert-ReinstallTargetPath -Name 'PgInstallRoot' -Path ([string]$config.PgInstallRoot) -BundleRoot $context.BundleRoot -PackageDirectory $context.PackageDirectory
    $pgData = Assert-ReinstallTargetPath -Name 'PgData' -Path ([string]$config.PgData) -BundleRoot $context.BundleRoot -PackageDirectory $context.PackageDirectory
    Assert-ReinstallDirectoryTarget -Name 'AppRoot' -Path $appRoot
    Assert-ReinstallDirectoryTarget -Name 'PgInstallRoot' -Path $pgInstallRoot
    Assert-ReinstallDirectoryTarget -Name 'PgData' -Path $pgData
    $pgCtl = Join-Path $pgInstallRoot 'bin\pg_ctl.exe'
    $appNssm = Join-Path $appRoot 'nssm.exe'

    $appService = Get-ReinstallServiceInfo -Name ([string]$config.AppServiceName)
    $pgService = Get-ReinstallServiceInfo -Name ([string]$config.PgServiceName)
    if ($null -ne $appService) { Assert-ReinstallServiceOwnership -Service $appService -ExpectedExecutable $appNssm }
    if ($null -ne $pgService) { Assert-ReinstallServiceOwnership -Service $pgService -ExpectedExecutable $pgCtl -ExpectedDataDirectory $pgData }
    Assert-ReinstallStateOwnership -StatePath $context.StatePath -Config $config -AppRoot $appRoot -PgInstallRoot $pgInstallRoot -PgData $pgData

    Write-DeployLog -Message 'Running package, operating-system, disk, port, and Redis preflight before any destructive cleanup.'
    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    & $powerShellExe '-NoLogo' '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' (Join-Path $PSScriptRoot 'Preflight.ps1') '-ConfigPath' $context.ConfigPath '-Reinstall'
    if ($LASTEXITCODE -ne 0) { throw 'Reinstall preflight failed. No files, services, or firewall rules were changed.' }

    if (-not (Test-ReinstallAppDirectory -Path $appRoot)) { throw "AppRoot is occupied by unknown files; refusing deletion: $appRoot" }
    if (-not (Test-ReinstallPostgresInstallDirectory -Path $pgInstallRoot)) { throw "PgInstallRoot is occupied by an unknown installation; refusing deletion: $pgInstallRoot" }
    if (-not (Test-ReinstallPostgresDataDirectory -Path $pgData)) { throw "PgData is occupied but is not a PostgreSQL cluster; refusing deletion: $pgData" }

    $firewallRuleName = Get-GpLinkFirewallRuleName -Port ([int]$config.ServerPort)
    $firewallRule = Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue
    if ($null -ne $firewallRule) {
        $description = [string]$firewallRule.Description
        if ([string]$firewallRule.Group -ne 'GPLink2 Offline Deployment' -or $description -notmatch '^Managed by GPLink2 offline bundle ') {
            throw "Existing firewall rule '$firewallRuleName' is not owned by a GPLink2 offline bundle; refusing deletion."
        }
    }

    Write-Host 'The following configured GPLink2 targets will be permanently removed:' -ForegroundColor Yellow
    Write-Host ("  Application:  {0}" -f $appRoot)
    Write-Host ("  PostgreSQL:   {0}" -f $pgInstallRoot)
    Write-Host ("  PostgreSQL DB:{0}" -f $pgData)
    Write-Host ("  Services:     {0}, {1}" -f $config.AppServiceName, $config.PgServiceName)
    Write-Host 'The offline package directory, Redis server, VC++ runtime, and OpenSSL runtime will not be removed.'
    Write-Host ''
    $confirmation = Read-Host 'Type RESET GPLink2 to continue (anything else cancels)'
    if ($confirmation -cne 'RESET GPLink2') {
        Write-DeployLog -Level 'WARN' -Message 'Destructive reinstall was cancelled before any changes.'
        exit 2
    }

    Write-DeployLog -Message 'Destructive reinstall confirmed by the operator.'
    Stop-AndDeleteReinstallService -Name ([string]$config.AppServiceName) -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)
    Stop-AndDeleteReinstallService -Name ([string]$config.PgServiceName) -TimeoutSeconds ([int]$config.ServiceStartTimeoutSeconds)
    if ($null -ne $firewallRule) {
        Remove-NetFirewallRule -Name $firewallRuleName
        Write-DeployLog -Message "Removed managed firewall rule '$firewallRuleName'."
    }
    Remove-ReinstallDirectory -Name 'GPLink2 application directory' -Path $appRoot
    Remove-ReinstallDirectory -Name 'PostgreSQL data directory' -Path $pgData
    Remove-ReinstallDirectory -Name 'PostgreSQL installation directory' -Path $pgInstallRoot
    if (Test-Path -LiteralPath $context.StatePath -PathType Leaf) {
        Remove-Item -LiteralPath $context.StatePath -Force
        Write-DeployLog -Message 'Removed the matching GPLink2 install state.'
    }

    Write-DeployLog -Message 'Old GPLink2 environment was removed. Starting the normal validated installation.'
    & $powerShellExe '-NoLogo' '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' (Join-Path $PSScriptRoot 'Install.ps1') '-ConfigPath' $context.ConfigPath
    if ($LASTEXITCODE -ne 0) { throw "The new installation failed with exit code $LASTEXITCODE. The old environment was already removed; review install and verify logs." }
    Write-DeployLog -Message 'Destructive reinstall and live verification completed successfully.'
    exit 0
} catch {
    $safeError = Protect-LogText -Text $_.Exception.Message
    Write-DeployLog -Level 'ERROR' -Message "Reinstall stopped: $safeError"
    Write-Error $safeError
    exit 1
}
