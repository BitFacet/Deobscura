param(
    [string]$Output = "Deobscura-snapshot.zip"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$outputPath = Join-Path $root $Output
$tempDir = Join-Path $env:TEMP ("deobscura-snapshot-" + [Guid]::NewGuid().ToString())

$include = @(
    "src",
    "gradle",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    ".gitignore"
)

$excludeDirs = @(
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "workspace"
)

try {
    if (Test-Path $outputPath) {
        Remove-Item $outputPath -Force
    }

    New-Item -ItemType Directory -Path $tempDir | Out-Null

    foreach ($item in $include) {
        $source = Join-Path $root $item

        if (-not (Test-Path $source)) {
            continue
        }

        $destination = Join-Path $tempDir $item

        if (Test-Path $source -PathType Container) {
            Copy-Item $source $destination -Recurse
        }
        else {
            $parent = Split-Path $destination -Parent
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
            Copy-Item $source $destination
        }
    }

    foreach ($dirName in $excludeDirs) {
        Get-ChildItem $tempDir -Directory -Recurse -Force |
                Where-Object { $_.Name -eq $dirName } |
                Remove-Item -Recurse -Force
    }

    Compress-Archive `
        -Path (Join-Path $tempDir "*") `
        -DestinationPath $outputPath `
        -CompressionLevel Optimal

    Write-Host "Created snapshot:"
    Write-Host $outputPath
}
finally {
    if (Test-Path $tempDir) {
        Remove-Item $tempDir -Recurse -Force
    }
}