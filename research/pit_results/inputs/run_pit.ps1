param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleCache = $env:GRADLE_USER_HOME,
    [string]$ReportDirectory = 'research/pit_results',
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Pass -JavaHome pointing to a JDK 21 installation.'
}
if (-not $GradleCache) {
    $GradleCache = Join-Path $env:USERPROFILE '.gradle'
}
if (-not (Test-Path -LiteralPath $GradleCache)) {
    New-Item -ItemType Directory -Path $GradleCache | Out-Null
}
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$cache = (Resolve-Path -LiteralPath $GradleCache).Path
$asciiRepository = Join-Path $env:TEMP 'apiary-pit-repository'
$asciiCache = Join-Path $env:TEMP 'apiary-pit-gradle-cache'
if ($asciiRepository -match '[^\x00-\x7F]' -or $asciiCache -match '[^\x00-\x7F]') {
    throw 'PIT on Windows requires an ASCII TEMP directory for its Java launcher argument files.'
}

function Set-VerifiedJunction([string]$Path, [string]$Target) {
    if (Test-Path -LiteralPath $Path) {
        $existing = Get-Item -LiteralPath $Path
        if ($existing.LinkType -ne 'Junction' -or $existing.Target -ne $Target) {
            throw "Existing helper path points elsewhere: $Path"
        }
    } else {
        New-Item -ItemType Junction -Path $Path -Target $Target | Out-Null
    }
}

Set-VerifiedJunction $asciiRepository $repository
Set-VerifiedJunction $asciiCache $cache
$previousJavaHome = $env:JAVA_HOME
$previousGradleCache = $env:GRADLE_USER_HOME
Push-Location -LiteralPath $asciiRepository
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $asciiCache
    $gradleArguments = @('--no-daemon', '--console=plain', '-I', 'research/pit.gradle',
                        "-PpitReportDir=$ReportDirectory", 'pitest')
    if ($Offline) {
        $gradleArguments += '--offline'
    }
    & '.\gradlew.bat' @gradleArguments
    $result = $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleCache
}
exit $result
