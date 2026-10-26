$ErrorActionPreference='Stop'
$localJar=Join-Path $PSScriptRoot 'application.jar'
$updater='C:/Users/wkc/.codex/skills/update-gplink2-test-env/scripts/update-gplink2-test-env.ps1'
& $updater -Mode Check -LocalJar $localJar | Tee-Object -FilePath (Join-Path $PSScriptRoot 'backend-check.txt')
& (Join-Path $PSScriptRoot 'Diagnostics.ps1') -Mode Enable
try {
    & $updater -Mode Deploy -LocalJar $localJar -Confirm:$false | Tee-Object -FilePath (Join-Path $PSScriptRoot 'backend-deploy.txt')
} catch {
    & (Join-Path $PSScriptRoot 'Diagnostics.ps1') -Mode Restore
    throw
}
