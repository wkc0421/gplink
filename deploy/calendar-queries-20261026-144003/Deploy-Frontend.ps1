param([switch]$Resume)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$buildRoot='E:\gplink_git\gplink\front\node_modules\.cache\gplink-release-calendar-20261026-144003'
$archive=Join-Path $PSScriptRoot 'frontend.zip'
$stamp='calendar-20261026-144003'
$siteRoot='D:\tool\nginx-1.26.3\html'
$target="$siteRoot\gpLink2"
$stage="$siteRoot\.gpLink2-stage-$stamp"
$backup="$siteRoot\gpLink2.backup-$stamp"
$remoteZip="$siteRoot\.gpLink2-$stamp.zip"
if (!(Test-Path -LiteralPath "$buildRoot\index.html")) { throw 'Built index is missing' }
if ((Test-Path -LiteralPath $archive) -and !$Resume) { throw 'Frontend archive already exists' }
$manifest=@(Get-ChildItem -LiteralPath $buildRoot -File -Recurse | ForEach-Object {
    [pscustomobject]@{Path=($_.FullName.Substring($buildRoot.Length+1) -replace '\\','/');
        Sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash; Length=$_.Length}
})
$manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'frontend-manifest.json') -Encoding UTF8
if (!(Test-Path -LiteralPath $archive)) { [IO.Compression.ZipFile]::CreateFromDirectory($buildRoot,$archive,[IO.Compression.CompressionLevel]::Optimal,$false) }
$zipHash=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
$credential=Import-Clixml -LiteralPath (Join-Path $env:TEMP 'gplink2-remote.credential.xml')
$session=New-PSSession -ComputerName 192.168.5.19 -Credential $credential
try {
    Invoke-Command -Session $session -ScriptBlock {
        param($Root,$Target,$Stage,$Backup,$Zip,$Resume)
        $rootFull=[IO.Path]::GetFullPath($Root).TrimEnd('\')
        foreach ($p in @($Target,$Stage,$Backup,$Zip)) {
            if (![IO.Path]::GetFullPath($p).StartsWith($rootFull+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Path outside intended web root' }
        }
        if (!(Test-Path -LiteralPath "$Target\index.html")) { throw 'Current site is missing' }
        if (Test-Path -LiteralPath $Backup) { throw 'Backup already exists' }
        if (!$Resume) { foreach ($p in @($Stage,$Zip)) { if (Test-Path -LiteralPath $p) { throw 'Deployment path already exists' } } }
    } -ArgumentList $siteRoot,$target,$stage,$backup,$remoteZip,[bool]$Resume
    if (!$Resume) { Copy-Item -LiteralPath $archive -Destination $remoteZip -ToSession $session }
    $manifestJson=$manifest | ConvertTo-Json -Depth 3 -Compress
    $staged=Invoke-Command -Session $session -ScriptBlock {
        param($Zip,$Stage,$ExpectedHash,$ManifestJson)
        if ((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash -ne $ExpectedHash) { throw 'Uploaded ZIP hash mismatch' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        if (!(Test-Path -LiteralPath $Stage)) { [IO.Compression.ZipFile]::ExtractToDirectory($Zip,$Stage) }
        $expected=$ManifestJson | ConvertFrom-Json
        $actual=@(Get-ChildItem -LiteralPath $Stage -File -Recurse | ForEach-Object {
            [pscustomobject]@{Path=($_.FullName.Substring($Stage.Length+1) -replace '\\','/');
                Sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash; Length=$_.Length}
        })
        if (Compare-Object $expected $actual -Property Path,Sha256,Length) { throw 'Staged frontend tree does not match local manifest' }
        [pscustomobject]@{Files=$actual.Count; Bytes=($actual | Measure-Object Length -Sum).Sum}
    } -ArgumentList $remoteZip,$stage,$zipHash,$manifestJson
    Invoke-Command -Session $session -ScriptBlock {
        param($Root,$Target,$Stage,$Backup)
        $rootFull=[IO.Path]::GetFullPath($Root).TrimEnd('\')
        foreach ($p in @($Target,$Stage,$Backup)) {
            if (![IO.Path]::GetFullPath($p).StartsWith($rootFull+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Move target outside intended web root' }
        }
        Move-Item -LiteralPath $Target -Destination $Backup
        try { Move-Item -LiteralPath $Stage -Destination $Target } catch {
            Move-Item -LiteralPath $Backup -Destination $Target
            throw
        }
    } -ArgumentList $siteRoot,$target,$stage,$backup
    $expectedIndex=($manifest | Where-Object Path -eq 'index.html').Sha256
    $web=New-Object Net.WebClient
    try {
        $bytes=$web.DownloadData('http://192.168.5.19:18088/index.html?calendar=20261026-144003')
        $liveHash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-','')
    } finally { $web.Dispose() }
    if ($liveHash -ne $expectedIndex) {
        Invoke-Command -Session $session -ScriptBlock {
            param($Root,$Target,$Backup,$Failed)
            $rootFull=[IO.Path]::GetFullPath($Root).TrimEnd('\')
            foreach ($p in @($Target,$Backup,$Failed)) {
                if (![IO.Path]::GetFullPath($p).StartsWith($rootFull+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Rollback path outside intended web root' }
            }
            Move-Item -LiteralPath $Target -Destination $Failed
            Move-Item -LiteralPath $Backup -Destination $Target
        } -ArgumentList $siteRoot,$target,$backup,"$siteRoot\gpLink2.failed-$stamp"
        throw 'Live frontend index mismatch; previous site restored'
    }
    $result=[pscustomobject]@{Action='Deployed'; Site=$target; Backup=$backup; Files=$staged.Files; Bytes=$staged.Bytes;
        ZipSha256=$zipHash; IndexSha256=$liveHash; Url='http://192.168.5.19:18088'}
    $result | ConvertTo-Json | Tee-Object -FilePath (Join-Path $PSScriptRoot 'frontend-deploy.json')
} finally { Remove-PSSession $session }
