param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$MeetingSdkRoot,
    [string]$SourceRoot,
    [string]$ArkuiXSdk,
    [string]$OpenHarmonySdk,
    [string]$BuildTools,
    [string]$BuildNode,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$scratchRoot = Join-Path $repoRoot '.arkui-x-validation'
$projectRoot = Join-Path $scratchRoot 'application/project'
$localTools = @{}
$toolFile = Join-Path $scratchRoot 'toolchain.local.json'
if (Test-Path -LiteralPath $toolFile) {
    $parsedTools = Get-Content -LiteralPath $toolFile -Raw | ConvertFrom-Json
    foreach ($property in $parsedTools.PSObject.Properties) { $localTools[$property.Name] = $property.Value }
}
function ResolveTool([string]$value, [string]$key, [string]$fallback) {
    if (-not $value) { $value = $localTools[$key] }
    if (-not $value) { $value = $fallback }
    return (Resolve-Path -LiteralPath $value).Path
}
$resolvedArkuiSdk = ResolveTool $ArkuiXSdk 'arkuiXSdk' (Join-Path $scratchRoot 'sdk')
$resolvedOhSdk = ResolveTool $OpenHarmonySdk 'openHarmonySdk' (Join-Path $scratchRoot 'openharmony-sdk')
$resolvedBuildTools = ResolveTool $BuildTools 'buildTools' (Join-Path $scratchRoot 'build-tools')
$buildNodeExe = ResolveTool $BuildNode 'buildNode' (Join-Path $scratchRoot 'node/node-v20.19.5-win-x64/node.exe')
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidSdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:MEETING_SDK_ROOT = (Resolve-Path -LiteralPath $MeetingSdkRoot).Path
# Local SDK paths are build parameters, never application source or committed state.
[System.IO.File]::WriteAllText((Join-Path $repoRoot 'local.properties'),
    'sdk.dir=' + $env:ANDROID_HOME.Replace([char]92,[char]47) + "`n")
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$env:ARKUIX_SDK_HOME = $resolvedArkuiSdk
$env:OpenHarmony_HOME = $resolvedOhSdk
$env:PATH = "$(Split-Path -Parent $buildNodeExe);$env:JAVA_HOME\bin;$env:PATH"
Push-Location $repoRoot
try {
    $bootstrapArguments = @('shared-frontend/bootstrap.mjs', '--check-toolchain', '--arkuix-sdk', $resolvedArkuiSdk,
        '--openharmony-sdk', $resolvedOhSdk, '--build-tools', $resolvedBuildTools, '--build-node', $buildNodeExe)
    if ($SourceRoot) { $bootstrapArguments += @('--source-root', (Resolve-Path -LiteralPath $SourceRoot).Path) }
    & $buildNodeExe @bootstrapArguments
    if ($LASTEXITCODE -ne 0) { throw 'Pinned toolchain/source bootstrap failed' }
    & $buildNodeExe shared-frontend/prepare.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Source preparation failed' }
    Push-Location $projectRoot
    try {
        & $buildNodeExe (Join-Path $resolvedBuildTools 'node_modules/@ohos/hvigor/bin/hvigor.js') --mode module -p module=entry@default -p product=default -p buildMode=debug assembleHap --no-daemon --no-parallel
        if ($LASTEXITCODE -ne 0) { throw 'ArkTS compilation failed' }
    } finally { Pop-Location }
    python experiments/arkui-x/package-android.py --project $projectRoot --sdk (Join-Path $resolvedArkuiSdk '20/arkui-x') --android-app (Join-Path $scratchRoot 'application/runtime')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed' }
    & $buildNodeExe shared-frontend/build-state.mjs seal
    if ($LASTEXITCODE -ne 0) { throw 'Compiled source provenance check failed' }
    $gradleArguments = @(':shared-app:assembleDebug', '--no-daemon', '--console=plain')
    if ($Offline) { $gradleArguments += '--offline' }
    & ./gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    $apk = Join-Path $repoRoot 'shared-app/build/outputs/apk/debug/shared-app-debug.apk'
    python -B shared-frontend/verify-meeting-native.py --sdk-root $env:MEETING_SDK_ROOT --ndk (Join-Path $env:ANDROID_HOME 'ndk/25.2.9519653') --apk $apk
    if ($LASTEXITCODE -ne 0) { throw 'Meeting native source/ABI/package validation failed' }
    python -B shared-frontend/audit-apk.py $apk --runtime-root (Join-Path $scratchRoot 'application/runtime') --output (Join-Path $scratchRoot 'application/apk-native-audit.json') --require-16kb
    if ($LASTEXITCODE -ne 0) { throw 'APK native 16 KB alignment gate failed' }
    & (Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK ZIP 16 KB alignment gate failed' }
} finally { Pop-Location }
