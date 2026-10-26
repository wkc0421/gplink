[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$bundleRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testPath = Join-Path $bundleRoot 'tests\OfflineDeployment.Tests.ps1'
if (-not (Get-Module -ListAvailable -Name Pester)) { throw 'Pester is not installed on this build machine.' }
$result = Invoke-Pester -Script $testPath -PassThru
if ($result.FailedCount -gt 0) { exit 1 }
exit 0
