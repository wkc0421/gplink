param(
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "generate_modbus_device_import_xlsx.js"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    node $scriptPath
} else {
    node $scriptPath --output $OutputPath
}

exit $LASTEXITCODE
