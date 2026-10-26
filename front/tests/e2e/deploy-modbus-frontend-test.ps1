$ErrorActionPreference = 'Stop'

$buildRoot = 'E:\gplink_git\gplink-modbus-live-test\front\dist'
$siteRoot = 'D:\tool\nginx-1.26.3\html'
$siteTarget = "$siteRoot\gpLink2"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archive = Join-Path $env:TEMP ("gpLink2-modbus-front-$stamp.zip")
$remoteArchive = "$siteRoot\.gpLink2-modbus-front-$stamp.zip"
$stage = "$siteRoot\.gpLink2-stage-$stamp"
$backup = "$siteRoot\gpLink2.backup-$stamp"
$credential = Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
$session = $null

if (-not (Test-Path -LiteralPath (Join-Path $buildRoot 'index.html'))) {
    throw "Built index is missing: $buildRoot"
}

$files = @(Get-ChildItem -LiteralPath $buildRoot -File -Recurse)
$expectedBytes = ($files | Measure-Object -Property Length -Sum).Sum
$expected = @($files | ForEach-Object {
    [pscustomobject]@{
        Path = $_.FullName.Substring($buildRoot.Length + 1).Replace('\', '/')
        Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
})

try {
    Compress-Archive -Path (Join-Path $buildRoot '*') -DestinationPath $archive -CompressionLevel Optimal -Force
    $session = New-PSSession -ComputerName '192.168.5.19' -Credential $credential

    $preflight = Invoke-Command -Session $session -ScriptBlock {
        param($root, $target, $stagePath, $backupPath, $archivePath)

        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            throw "Web root missing: $root"
        }
        if (-not (Test-Path -LiteralPath (Join-Path $target 'index.html') -PathType Leaf)) {
            throw "Current site missing: $target"
        }
        foreach ($path in @($stagePath, $backupPath, $archivePath)) {
            if (Test-Path -LiteralPath $path) {
                throw "Deployment path already exists: $path"
            }
        }
        [pscustomobject]@{
            Root = (Get-Item -LiteralPath $root).FullName
            Target = (Get-Item -LiteralPath $target).FullName
            CurrentIndexHash = (Get-FileHash -LiteralPath (Join-Path $target 'index.html') -Algorithm SHA256).Hash
        }
    } -ArgumentList $siteRoot, $siteTarget, $stage, $backup, $remoteArchive
    $preflight | Format-List

    Copy-Item -LiteralPath $archive -Destination $remoteArchive -ToSession $session
    $actual = @(Invoke-Command -Session $session -ScriptBlock {
        param($zipPath, $stagePath)

        New-Item -ItemType Directory -Path $stagePath | Out-Null
        Expand-Archive -LiteralPath $zipPath -DestinationPath $stagePath
        if (-not (Test-Path -LiteralPath (Join-Path $stagePath 'index.html') -PathType Leaf)) {
            throw 'Staged frontend has no index.html'
        }
        Get-ChildItem -LiteralPath $stagePath -File -Recurse | ForEach-Object {
            [pscustomobject]@{
                Path = $_.FullName.Substring($stagePath.Length + 1).Replace('\', '/')
                Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        }
    } -ArgumentList $remoteArchive, $stage)

    $diff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual -Property Path, Hash
    if ($diff) {
        throw "Staged frontend hash mismatch: $($diff.Count) entries differ."
    }

    Invoke-Command -Session $session -ScriptBlock {
        param($target, $stagePath, $backupPath)

        Move-Item -LiteralPath $target -Destination $backupPath
        try {
            Move-Item -LiteralPath $stagePath -Destination $target
        }
        catch {
            if (-not (Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $backupPath)) {
                Move-Item -LiteralPath $backupPath -Destination $target
            }
            throw
        }
    } -ArgumentList $siteTarget, $stage, $backup

    $expectedIndexHash = ($expected | Where-Object Path -eq 'index.html' | Select-Object -First 1).Hash
    $webClient = New-Object System.Net.WebClient
    $indexBytes = $webClient.DownloadData('http://192.168.5.19:18088/index.html')
    $liveIndexHash = [BitConverter]::ToString(
        [Security.Cryptography.SHA256]::Create().ComputeHash($indexBytes)
    ).Replace('-', '')
    if ($liveIndexHash -ne $expectedIndexHash) {
        Invoke-Command -Session $session -ScriptBlock {
            param($target, $backupPath, $failedPath)

            if (Test-Path -LiteralPath $target) {
                Move-Item -LiteralPath $target -Destination $failedPath
            }
            Move-Item -LiteralPath $backupPath -Destination $target
        } -ArgumentList $siteTarget, $backup, "$siteRoot\gpLink2.failed-$stamp"
        throw "Live index hash mismatch; previous site restored. expected=$expectedIndexHash actual=$liveIndexHash"
    }

    Invoke-Command -Session $session -ScriptBlock {
        param($path)
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    } -ArgumentList $remoteArchive

    [pscustomobject]@{
        Action = 'Frontend deployed'
        Files = $actual.Count
        Bytes = $expectedBytes
        Backup = $backup
        IndexSha256 = $liveIndexHash
        Url = 'http://192.168.5.19:18088'
    } | Format-List
}
finally {
    if ($session) {
        Remove-PSSession $session
    }
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
}
