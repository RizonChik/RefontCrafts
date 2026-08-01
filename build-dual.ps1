$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$pom = [xml](Get-Content -Raw -Encoding UTF8 'pom.xml')
$version = [string]$pom.project.version
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sourceJar = Join-Path $root "target\RefontCrafts-$version.jar"

$jarExe = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $jarExe = $candidate
    }
}
if (-not $jarExe) {
    $jarCommand = Get-Command 'jar.exe' -ErrorAction SilentlyContinue
    if ($jarCommand) {
        $jarExe = $jarCommand.Source
    }
}
if (-not $jarExe) {
    throw 'jar.exe was not found. Set JAVA_HOME to a JDK installation.'
}

if (Test-Path -LiteralPath $sourceJar -PathType Leaf) {
    $preservedJar = Join-Path $root "target\RefontCrafts-$version-prebuild-$stamp.jar"
    Copy-Item -LiteralPath $sourceJar -Destination $preservedJar
    Write-Host "Preserved existing target JAR: $preservedJar"
}

Write-Host "Building RefontCrafts $version..."
mvn package -DskipTests

if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
    throw "Maven did not produce $sourceJar"
}

$ruDir = Join-Path $root 'ru-builds'
$enDir = Join-Path $root 'en-builds'
New-Item -ItemType Directory -Force -Path $ruDir, $enDir | Out-Null

$ruJar = Join-Path $ruDir "RefontCrafts-$version.jar"
$enJar = Join-Path $enDir "RefontCrafts-$version.jar"
Copy-Item -LiteralPath $sourceJar -Destination $enJar -Force
Copy-Item -LiteralPath $sourceJar -Destination $ruJar -Force

$temp = Join-Path ([IO.Path]::GetTempPath()) "RefontCrafts-dual-$stamp"
New-Item -ItemType Directory -Force -Path $temp | Out-Null

try {
    $config = Get-Content -Raw -Encoding UTF8 'src\main\resources\config.yml'
    $config = [regex]::Replace($config, '(?m)^  language:.*$', '  language: "ru"')
    $configPath = Join-Path $temp 'config.yml'
    [IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))

    $tempJar = Join-Path $temp 'RefontCrafts-ru.jar'
    Copy-Item -LiteralPath $ruJar -Destination $tempJar -Force
    $jarProcess = Start-Process -FilePath $jarExe -ArgumentList @('uf', 'RefontCrafts-ru.jar', 'config.yml') -WorkingDirectory $temp -Wait -PassThru -NoNewWindow
    if ($jarProcess.ExitCode -ne 0) {
        throw "Could not replace config.yml inside the Russian JAR. Exit code: $($jarProcess.ExitCode)"
    }
    Copy-Item -LiteralPath $tempJar -Destination $ruJar -Force
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Russian build: $ruJar"
Write-Host "English build: $enJar"
Write-Host 'Old target JARs were not deleted.'
