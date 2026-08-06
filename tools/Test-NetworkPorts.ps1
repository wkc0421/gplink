[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$Target = "127.0.0.1",

    [Parameter(Mandatory = $false)]
    [int[]]$Ports = @(3389, 5985, 5986, 8848),

    [Parameter(Mandatory = $false)]
    [string]$OutputDirectory = "."
)

$ErrorActionPreference = "Continue"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectoryPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$logPath = Join-Path $outputDirectoryPath ("network-diagnostic-{0}.txt" -f $timestamp)

New-Item -ItemType Directory -Path $outputDirectoryPath -Force | Out-Null

function Write-Result {
    param([string]$Message)
    $Message | Tee-Object -FilePath $logPath -Append
}

Write-Result "=== Network diagnostic ==="
Write-Result ("Time: {0}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"))
Write-Result ("Computer: {0}" -f $env:COMPUTERNAME)
Write-Result ("User: {0}" -f ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name))
Write-Result ("Target: {0}" -f $Target)
Write-Result ("Ports: {0}" -f ($Ports -join ", "))
Write-Result ""

Write-Result "--- Basic connectivity ---"
try {
    $ping = Test-Connection -ComputerName $Target -Count 2 -ErrorAction Stop
    Write-Result ("Ping: PASS; replies={0}; averageMs={1}" -f $ping.Count, [math]::Round((($ping | Measure-Object -Property ResponseTime -Average).Average), 2))
}
catch {
    Write-Result ("Ping: FAIL; {0}" -f $_.Exception.Message)
}

try {
    $resolved = [System.Net.Dns]::GetHostAddresses($Target) | ForEach-Object { $_.IPAddressToString }
    Write-Result ("DNS/IP: {0}" -f ($resolved -join ", "))
}
catch {
    Write-Result ("DNS/IP: FAIL; {0}" -f $_.Exception.Message)
}

Write-Result ""
Write-Result "--- TCP port tests from this computer ---"
foreach ($port in $Ports) {
    try {
        $test = Test-NetConnection -ComputerName $Target -Port $port -InformationLevel Detailed -WarningAction SilentlyContinue
        $remoteAddress = if ($test.RemoteAddress) { $test.RemoteAddress.IPAddressToString } else { "unknown" }
        Write-Result ("TCP {0}: {1}; remoteAddress={2}; latencyMs={3}" -f $port, $(if ($test.TcpTestSucceeded) { "OPEN" } else { "CLOSED_OR_BLOCKED" }), $remoteAddress, $test.PingReplyDetails.RoundtripTime)
    }
    catch {
        Write-Result ("TCP {0}: ERROR; {1}" -f $port, $_.Exception.Message)
    }
}

Write-Result ""
Write-Result "--- Local machine diagnostics (useful when run on the target server) ---"
try {
    $profiles = Get-NetFirewallProfile | Select-Object Name, Enabled, DefaultInboundAction, DefaultOutboundAction
    $profiles | Format-Table -AutoSize | Out-String | Tee-Object -FilePath $logPath -Append
}
catch {
    Write-Result ("Firewall profiles: ERROR; {0}" -f $_.Exception.Message)
}

try {
    $serviceNames = @("TermService", "WinRM")
    Get-Service -Name $serviceNames -ErrorAction SilentlyContinue |
        Select-Object Name, Status, StartType |
        Format-Table -AutoSize | Out-String | Tee-Object -FilePath $logPath -Append
}
catch {
    Write-Result ("Services: ERROR; {0}" -f $_.Exception.Message)
}

try {
    $listeners = Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $Ports -contains $_.LocalPort } |
        Sort-Object LocalPort, LocalAddress |
        Select-Object LocalAddress, LocalPort, OwningProcess
    if ($listeners) {
        $listeners | Format-Table -AutoSize | Out-String | Tee-Object -FilePath $logPath -Append
    }
    else {
        Write-Result "Matching local TCP listeners: none"
    }
}
catch {
    Write-Result ("Local TCP listeners: ERROR; {0}" -f $_.Exception.Message)
}

Write-Result ""
Write-Result "=== End; no configuration was changed ==="
Write-Result ("Log: {0}" -f $logPath)

Write-Host ""
Write-Host ("诊断完成，日志已保存到: {0}" -f $logPath) -ForegroundColor Green
