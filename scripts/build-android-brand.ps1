param(
    [string]$Config = "android-app/branding/branding.json",
    [string]$Output = "releaseApk"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$configPath = if ([System.IO.Path]::IsPathRooted($Config)) { $Config } else { Join-Path $repoRoot $Config }
if (!(Test-Path $configPath)) {
    throw "Branding config not found: $configPath"
}

$configDir = Split-Path $configPath -Parent
$configJson = Get-Content -Raw $configPath | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($configJson.appName)) {
    throw "branding.json requires appName"
}
if ([string]::IsNullOrWhiteSpace($configJson.iconSource)) {
    throw "branding.json requires iconSource"
}

function Resolve-BrandPath([string]$value) {
    if ([System.IO.Path]::IsPathRooted($value)) { return $value }
    return (Join-Path $configDir $value)
}

$iconPath = Resolve-BrandPath $configJson.iconSource
if (!(Test-Path $iconPath)) {
    throw "Icon source not found: $iconPath"
}

$roundIconPath = if (![string]::IsNullOrWhiteSpace($configJson.roundIconSource)) {
    Resolve-BrandPath $configJson.roundIconSource
} else {
    $iconPath
}
if (!(Test-Path $roundIconPath)) {
    throw "Round icon source not found: $roundIconPath"
}

$resDir = Join-Path $repoRoot "android-app/app/src/main/res"
$iconScript = Join-Path $repoRoot "scripts/generate_android_icons.py"
if (!(Test-Path $iconScript)) {
    throw "Icon generator missing: $iconScript"
}

$python = Get-Command py -ErrorAction SilentlyContinue
if ($python) {
    & py -3 $iconScript --icon $iconPath --round-icon $roundIconPath --res-dir $resDir
} else {
    & python $iconScript --icon $iconPath --round-icon $roundIconPath --res-dir $resDir
}

$signing = $configJson.signing
if ($null -eq $signing) {
    throw "branding.json requires signing block for signed release"
}

$keystorePath = Resolve-BrandPath $signing.keystorePath
if (!(Test-Path $keystorePath)) {
    throw "Keystore not found: $keystorePath"
}
if ([string]::IsNullOrWhiteSpace($signing.keyAlias)) {
    throw "signing.keyAlias is required"
}
if ([string]::IsNullOrWhiteSpace($signing.storePasswordEnv)) {
    throw "signing.storePasswordEnv is required"
}
if ([string]::IsNullOrWhiteSpace($signing.keyPasswordEnv)) {
    throw "signing.keyPasswordEnv is required"
}

$storePassword = [Environment]::GetEnvironmentVariable([string]$signing.storePasswordEnv)
$keyPassword = [Environment]::GetEnvironmentVariable([string]$signing.keyPasswordEnv)
if ([string]::IsNullOrWhiteSpace($storePassword)) {
    throw "Missing env var for keystore password: $($signing.storePasswordEnv)"
}
if ([string]::IsNullOrWhiteSpace($keyPassword)) {
    throw "Missing env var for key password: $($signing.keyPasswordEnv)"
}

$gradleWrapper = Join-Path $repoRoot "android-app/gradlew.bat"
if (!(Test-Path $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

$appIdSuffix = [string]$configJson.applicationIdSuffix
$versionSuffix = [string]$configJson.versionNameSuffix

$gradleArgs = @(
    ":app:assembleRelease",
    "-PbrandAppName=$($configJson.appName)",
    "-PbrandApplicationIdSuffix=$appIdSuffix",
    "-PbrandVersionNameSuffix=$versionSuffix",
    "-PbrandStoreFile=$keystorePath",
    "-PbrandStorePassword=$storePassword",
    "-PbrandKeyAlias=$($signing.keyAlias)",
    "-PbrandKeyPassword=$keyPassword"
)

Push-Location (Join-Path $repoRoot "android-app")
try {
    & .\gradlew.bat @gradleArgs
} finally {
    Pop-Location
}

$apkPath = Join-Path $repoRoot "android-app/app/build/outputs/apk/release/app-release.apk"
if (!(Test-Path $apkPath)) {
    throw "Release APK not found after build: $apkPath"
}

if ($Output -ne "releaseApk") {
    $target = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $repoRoot $Output }
    Copy-Item -Force $apkPath $target
    Write-Host "Branded release APK copied to: $target"
} else {
    Write-Host "Branded release APK: $apkPath"
}
